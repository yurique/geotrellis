/*
 * Copyright 2017 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.io.cog

import geotrellis.raster.{CellGrid, RasterExtent}
import geotrellis.raster.io.geotiff.reader.GeoTiffReader
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.index.{Index, IndexRanges, MergeQueue}
import geotrellis.util._
import geotrellis.spark.util.KryoWrapper
import org.apache.spark.rdd._
import spray.json._
import org.apache.spark.SparkContext
import java.net.URI

import geotrellis.raster.io.geotiff.reader.TiffTagsReader

import scala.reflect._

abstract class FilteringCOGLayerReader[ID] extends COGLayerReader[ID] {

  val attributeStore: AttributeStore

  /** read
    *
    * This function will read an RDD layer based on a query.
    *
    * @param id              The ID of the layer to be read
    * @param rasterQuery     The query that will specify the filter for this read.
    * @param numPartitions   The desired number of partitions in the resulting RDD.
    *
    * @tparam K              Type of RDD Key (ex: SpatialKey)
    * @tparam V              Type of RDD Value (ex: Tile or MultibandTile )
    */
  def read[
    K: SpatialComponent: Boundable: JsonFormat: ClassTag,
    V <: CellGrid: GeoTiffReader: ClassTag
  ](id: ID, rasterQuery: LayerQuery[K, TileLayerMetadata[K]], numPartitions: Int): RDD[(K, V)] with Metadata[TileLayerMetadata[K]]

  def baseRead[
    K: SpatialComponent: Boundable: JsonFormat: ClassTag,
    V <: CellGrid: GeoTiffReader: ClassTag
  ](
    id: LayerId,
    tileQuery: LayerQuery[K, TileLayerMetadata[K]],
    numPartitions: Int,
    getKeyPath: (ZoomRange, Int) => BigInt => String,
    pathExists: String => Boolean, // check the path above exists
    fullPath: String => URI, // add an fs prefix
    defaultThreads: Int
  )(implicit sc: SparkContext,
             getByteReader: URI => ByteReader,
             idToLayerId: ID => LayerId
  ): RDD[(K, V)] with Metadata[TileLayerMetadata[K]] = {

    val COGLayerStorageMetadata(cogLayerMetadata, keyIndexes) =
      try {
        attributeStore.read[COGLayerStorageMetadata[K]](LayerId(id.name, 0), "cog_metadata")
      } catch {
        // to follow GeoTrellis Layer Readers logic
        case e: AttributeNotFoundError => throw new LayerNotFoundError(id).initCause(e)
      }

    val metadata = cogLayerMetadata.tileLayerMetadata(id.zoom)

    val queryKeyBounds: Seq[KeyBounds[K]] = tileQuery(metadata)

    val readDefinitions: Seq[(ZoomRange, Seq[(SpatialKey, Int, TileBounds, Seq[(TileBounds, SpatialKey)])])] =
      cogLayerMetadata.getReadDefinitions(queryKeyBounds, id.zoom)

    readDefinitions.headOption.map(_._1) match {
      case Some(zoomRange) => {
        val baseKeyIndex = keyIndexes(zoomRange)

        val maxWidth = Index.digits(baseKeyIndex.toIndex(baseKeyIndex.keyBounds.maxKey))
        val keyPath: BigInt => String = getKeyPath(zoomRange, maxWidth)
        val decompose = (bounds: KeyBounds[K]) => baseKeyIndex.indexRanges(bounds)

        val baseLayout = cogLayerMetadata.layoutForZoom(zoomRange.minZoom)
        val layout = cogLayerMetadata.layoutForZoom(id.zoom)

        val baseKeyBounds = cogLayerMetadata.zoomRangeInfoFor(zoomRange.minZoom)._2

        def transformKeyBounds(keyBounds: KeyBounds[K]): KeyBounds[K] = {
          val KeyBounds(minKey, maxKey) = keyBounds
          val extent = layout.extent
          val sourceRe = RasterExtent(extent, layout.layoutCols, layout.layoutRows)
          val targetRe = RasterExtent(extent, baseLayout.layoutCols, baseLayout.layoutRows)

          val minSpatialKey = minKey.getComponent[SpatialKey]
          val (minCol, minRow) = {
            val (x, y) = sourceRe.gridToMap(minSpatialKey.col, minSpatialKey.row)
            targetRe.mapToGrid(x, y)
          }

          val maxSpatialKey = maxKey.getComponent[SpatialKey]
          val (maxCol, maxRow) = {
            val (x, y) = sourceRe.gridToMap(maxSpatialKey.col, maxSpatialKey.row)
            targetRe.mapToGrid(x, y)
          }

          KeyBounds(
            minKey.setComponent(SpatialKey(minCol, minRow)),
            maxKey.setComponent(SpatialKey(maxCol, maxRow))
          )
        }

        val baseQueryKeyBounds: Seq[KeyBounds[K]] =
          queryKeyBounds
            .flatMap { qkb =>
              transformKeyBounds(qkb).intersect(baseKeyBounds) match {
                case EmptyBounds => None
                case kb: KeyBounds[K] => Some(kb)
              }
            }
            .distinct

        val rdd =
          FilteringCOGLayerReader
            .read[K, V](
              keyPath = keyPath,
              pathExists = pathExists,
              fullPath = fullPath,
              baseQueryKeyBounds = baseQueryKeyBounds,
              decomposeBounds = decompose,
              readDefinitions = readDefinitions.flatMap(_._2).groupBy(_._1),
              threads = defaultThreads,
              numPartitions = Some(numPartitions)
            )

        new ContextRDD(rdd, metadata)
      }
      case None =>
        new ContextRDD(sc.parallelize(Seq()), metadata.setComponent[Bounds[K]](EmptyBounds))
    }
  }

  def read[
    K: SpatialComponent: Boundable: JsonFormat: ClassTag,
    V <: CellGrid: GeoTiffReader: ClassTag
  ](id: ID, rasterQuery: LayerQuery[K, TileLayerMetadata[K]]): RDD[(K, V)] with Metadata[TileLayerMetadata[K]] =
    read(id, rasterQuery, defaultNumPartitions)

  def read[
    K: SpatialComponent: Boundable: JsonFormat: ClassTag,
    V <: CellGrid: GeoTiffReader: ClassTag
  ](id: ID, numPartitions: Int): RDD[(K, V)] with Metadata[TileLayerMetadata[K]] =
    read(id, new LayerQuery[K, TileLayerMetadata[K]], numPartitions)

  def query[
    K: SpatialComponent: Boundable: JsonFormat: ClassTag,
    V <: CellGrid: GeoTiffReader: ClassTag
  ](layerId: ID): BoundLayerQuery[K, TileLayerMetadata[K], RDD[(K, V)] with Metadata[TileLayerMetadata[K]]] =
    new BoundLayerQuery(new LayerQuery, read(layerId, _))

  def query[
    K: SpatialComponent: Boundable: JsonFormat: ClassTag,
    V <: CellGrid: GeoTiffReader: ClassTag
  ](layerId: ID, numPartitions: Int): BoundLayerQuery[K, TileLayerMetadata[K], RDD[(K, V)] with Metadata[TileLayerMetadata[K]]] =
    new BoundLayerQuery(new LayerQuery, read(layerId, _, numPartitions))
}

object FilteringCOGLayerReader {
  def read[
    K: SpatialComponent: Boundable: JsonFormat: ClassTag,
    V <: CellGrid: GeoTiffReader
  ](
     keyPath: BigInt => String, // keyPath
     pathExists: String => Boolean, // check the path above exists
     fullPath: String => URI, // add an fs prefix
     baseQueryKeyBounds: Seq[KeyBounds[K]], // each key here represents a COG filename
     decomposeBounds: KeyBounds[K] => Seq[(BigInt, BigInt)],
     readDefinitions: Map[SpatialKey, Seq[(SpatialKey, Int, TileBounds, Seq[(TileBounds, SpatialKey)])]],
     threads: Int,
     numPartitions: Option[Int] = None
   )(implicit sc: SparkContext, getByteReader: URI => ByteReader): RDD[(K, V)] = {
    if (baseQueryKeyBounds.isEmpty) return sc.emptyRDD[(K, V)]

    val kwFormat = KryoWrapper(implicitly[JsonFormat[K]])

    val ranges = if (baseQueryKeyBounds.length > 1)
      MergeQueue(baseQueryKeyBounds.flatMap(decomposeBounds))
    else
      baseQueryKeyBounds.flatMap(decomposeBounds)

    val bins = IndexRanges.bin(ranges, numPartitions.getOrElse(sc.defaultParallelism))

    sc.parallelize(bins, bins.size)
      .mapPartitions { partition: Iterator[Seq[(BigInt, BigInt)]] =>
        val keyFormat = kwFormat.value

        partition flatMap { seq =>
          LayerReader.njoin[K, V](seq.toIterator, threads) { index: BigInt =>
            if (!pathExists(keyPath(index))) Vector()
            else {
              val uri = fullPath(keyPath(index))
              val byteReader: ByteReader = uri
              val baseKey = TiffTagsReader
                .read(byteReader)
                .tags
                .headTags(GTKey)
                .parseJson
                .convertTo[K](keyFormat)

              readDefinitions
                .get(baseKey.getComponent[SpatialKey])
                .toVector
                .flatten
                .flatMap { case (spatialKey, overviewIndex, _, seq) =>
                  val key = baseKey.setComponent(spatialKey)
                  val tiff = GeoTiffReader[V].read(uri, decompress = false, streaming = true).getOverview(overviewIndex)
                  val map = seq.map { case (gb, sk) => gb -> key.setComponent(sk) }.toMap

                  tiff
                    .crop(map.keys.toSeq)
                    .flatMap { case (k, v) => map.get(k).map(i => i -> v) }
                    .toVector
                }
            }
          }
        }
      }
  }
}
