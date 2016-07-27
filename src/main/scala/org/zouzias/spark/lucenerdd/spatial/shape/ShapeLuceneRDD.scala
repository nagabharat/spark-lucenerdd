/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zouzias.spark.lucenerdd.spatial.shape

import com.spatial4j.core.shape.Shape
import com.twitter.algebird.TopK
import org.apache.lucene.document.Document
import org.apache.lucene.spatial.query.SpatialOperation
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark._
import org.zouzias.spark.lucenerdd.aggregate.SparkScoreDocAggregatable
import org.zouzias.spark.lucenerdd.models.SparkScoreDoc
import org.zouzias.spark.lucenerdd.query.LuceneQueryHelpers
import org.zouzias.spark.lucenerdd.spatial.shape.partition.{AbstractShapeLuceneRDDPartition, ShapeLuceneRDDPartition}

import scala.reflect.ClassTag

/**
 * ShapeLuceneRDD for geospatial and full-text search queries
 *
 * @param partitionsRDD
 * @tparam K Type containing the geospatial information (must be implicitly convertet to [[Shape]])
 * @tparam V Type containing remaining information (must be implicitly converted to [[Document]])
 */
class ShapeLuceneRDD[K: ClassTag, V: ClassTag]
  (private val partitionsRDD: RDD[AbstractShapeLuceneRDDPartition[K, V]])
  extends RDD[(K, V)](partitionsRDD.context, List(new OneToOneDependency(partitionsRDD)))
    with SparkScoreDocAggregatable
    with Logging {

  logInfo("Instance is created...")
  logInfo(s"Number of partitions: ${partitionsRDD.count()}")

  override protected def getPartitions: Array[Partition] = partitionsRDD.partitions

  override protected def getPreferredLocations(s: Partition): Seq[String] =
    partitionsRDD.preferredLocations(s)

  override def persist(newLevel: StorageLevel): this.type = {
    partitionsRDD.persist(newLevel)
    this
  }

  override def unpersist(blocking: Boolean = true): this.type = {
    partitionsRDD.unpersist(blocking)
    this
  }

  override def setName(_name: String): this.type = {
    if (partitionsRDD.name != null) {
      partitionsRDD.setName(partitionsRDD.name + ", " + _name)
    } else {
      partitionsRDD.setName(_name)
    }
    this
  }

  setName("ShapeLuceneRDD")

  /**
   * Aggregates Lucene documents using monoidal structure, i.e., [[SparkDocTopKMonoid]]
   *
   * TODO: Move to aggregations
   *
   * @param f
   * @return
   */
  private def docResultsAggregator
  (f: AbstractShapeLuceneRDDPartition[K, V] => Iterable[SparkScoreDoc])
  : List[SparkScoreDoc] = {
    val parts = partitionsRDD.map(f(_)).map(x => SparkDocTopKMonoid.build(x))
    parts.reduce( (x, y) => SparkDocTopKMonoid.plus(x, y)).items
  }

  /**
   * Link entities based on k-nearest neighbors (Knn)
   *
   * Links this and that based on nearest neighbors, returns Knn
   *
   * @param that An RDD of entities to be linked
   * @param pointFunctor Function that generates a point from each element of other
   * @tparam T A type
   * @return an RDD of Tuple2 that contains the linked results
   *
   * Note: Currently the query coordinates of the other RDD are collected to the driver and
   * broadcast to the workers.
   */
  def linkByKnn[T: ClassTag](that: RDD[T], pointFunctor: T => (Double, Double),
                           topK: Int = DefaultTopK)
  : RDD[(T, List[SparkScoreDoc])] = {
    val queries = that.map(pointFunctor).collect()
    val queriesB = partitionsRDD.context.broadcast(queries)

    val resultsByPart: RDD[(Long, TopK[SparkScoreDoc])] = partitionsRDD.flatMap {
      case partition => queriesB.value.zipWithIndex.map { case (queryPoint, index) =>
        val results = partition.knnSearch(queryPoint, topK, LuceneQueryHelpers.MatchAllDocsString)
          .reverse.map(x => SparkDocTopKMonoid.build(x))
        if (results.nonEmpty) {
          (index.toLong, results.reduce( (x, y) => SparkDocTopKMonoid.plus(x, y)))
        }
        else {
          (index.toLong, SparkDocTopKMonoid.zero)
        }
      }
    }

    val results = resultsByPart.reduceByKey( (x, y) => SparkDocTopKMonoid.plus(x, y))
    that.zipWithIndex.map(_.swap).join(results)
      .map{ case (_, joined) => (joined._1, joined._2.items.reverse.take(topK))}
  }

  /**
   * K-nearest neighbors search
   *
   * @param queryPoint query point (X, Y)
   * @param k number of nearest neighbor points to return
   * @param searchString Lucene query string
   * @return
   */
  def knnSearch(queryPoint: (Double, Double), k: Int,
                searchString: String = LuceneQueryHelpers.MatchAllDocsString)
  : Iterable[SparkScoreDoc] = {
    docResultsAggregator(_.knnSearch(queryPoint, k, searchString).reverse).reverse.take(k)
  }

  /**
   * Search for points within a circle
   *
   * @param center center of circle
   * @param radius radius of circle in kilometers (KM)
   * @param k number of points to return
   * @return
   */
  def circleSearch(center: (Double, Double), radius: Double, k: Int)
  : Iterable[SparkScoreDoc] = {
    // Points can only intersect
    docResultsAggregator(_.circleSearch(center, radius, k,
      SpatialOperation.Intersects.getName)).take(k)
  }

  /**
   * Spatial search with arbitrary shape
   *
   * @param shapeWKT Shape in WKT format
   * @param k Number of element to return
   * @param operationName
   * @return
   */
  def spatialSearch(shapeWKT: String, k: Int,
                    operationName: String = SpatialOperation.Intersects.getName)
  : Iterable[SparkScoreDoc] = {
    logInfo(s"Spatial search with shape ${shapeWKT} and operation ${operationName}")
    docResultsAggregator(_.spatialSearch(shapeWKT, k, operationName)).take(k)
  }

  /**
   * Spatial search with a single Point
   *
   * @param point
   * @param k
   * @param operationName
   * @return
   */
  def spatialSearch(point: (Double, Double), k: Int,
                    operationName: String)
  : Iterable[SparkScoreDoc] = {
    logInfo(s"Spatial search with point ${point} and operation ${operationName}")
    docResultsAggregator(_.spatialSearch(point, k, operationName)).take(k)
  }

  /**
   * Bounding box search with center and radius
   *
   * @param center given as (x, y)
   * @param radius in kilometers (KM)
   * @param k
   * @param operationName
   * @return
   */
  def bboxSearch(center: (Double, Double), radius: Double, k: Int,
                    operationName: String = SpatialOperation.Intersects.getName)
  : Iterable[SparkScoreDoc] = {
    logInfo(s"Bounding box with center ${center}, radius ${radius}, k = ${k}")
    docResultsAggregator(_.bboxSearch(center, radius, k, operationName)).take(k)
  }

  /**
   * Bounding box search with rectangle
   * @param lowerLeft Lower left corner
   * @param upperRight Upper right corner
   * @param k
   * @param operationName Intersect, contained, etc.
   * @return
   */
  def bboxSearch(lowerLeft: (Double, Double), upperRight: (Double, Double), k: Int,
                 operationName: String)
  : Iterable[SparkScoreDoc] = {
    logInfo(s"Bounding box with lower left ${lowerLeft}, upper right ${upperRight} and k = ${k}")
    docResultsAggregator(_.bboxSearch(lowerLeft, upperRight, k, operationName)).take(k)
  }

  override def count(): Long = {
    logInfo("Count requested")
    partitionsRDD.map(_.size).reduce(_ + _)
  }

  /** RDD compute method. */
  override def compute(part: Partition, context: TaskContext): Iterator[(K, V)] = {
    firstParent[AbstractShapeLuceneRDDPartition[K, V]].iterator(part, context).next.iterator
  }

  def filter(pred: (K, V) => Boolean): ShapeLuceneRDD[K, V] = {
    val newPartitionRDD = partitionsRDD.mapPartitions(partition =>
      partition.map(_.filter(pred)), preservesPartitioning = true
    )
    new ShapeLuceneRDD(newPartitionRDD)
  }

  def exists(elem: K): Boolean = {
    partitionsRDD.map(_.isDefined(elem)).collect().exists(x => x)
  }

  def close(): Unit = {
    logInfo(s"Closing...")
    partitionsRDD.foreach(_.close())
  }
}

object ShapeLuceneRDD {

  /**
   * Instantiate a ShapeLuceneRDD given an RDD[T]
   *
   * @param elems RDD of type T
   * @return
   */
  def apply[K: ClassTag, V: ClassTag](elems: RDD[(K, V)])
                                     (implicit shapeConv: K => Shape,
                                      docConverter: V => Document)
  : ShapeLuceneRDD[K, V] = {
    val partitions = elems.mapPartitions[AbstractShapeLuceneRDDPartition[K, V]](
      iter => Iterator(ShapeLuceneRDDPartition[K, V](iter)),
      preservesPartitioning = true)
    new ShapeLuceneRDD(partitions)
  }

  /**
   * Instantiate a ShapeLuceneRDD with an iterable
   *
   * @param elems
   * @param sc
   * @return
   */
  def apply[K: ClassTag, V: ClassTag]
  (elems: Iterable[(K, V)])(implicit sc: SparkContext, shapeConv: K => Shape,
                            docConverter: V => Document): ShapeLuceneRDD[K, V] = {
    apply(sc.parallelize[(K, V)](elems.toSeq))
  }
}
