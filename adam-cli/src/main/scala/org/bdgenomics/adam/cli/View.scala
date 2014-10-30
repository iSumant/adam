/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.adam.cli

import org.apache.hadoop.mapreduce.Job
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.bdgenomics.adam.projections.{ AlignmentRecordField, Projection }
import org.bdgenomics.adam.rdd.ADAMSaveArgs
import org.bdgenomics.adam.rdd.read.AlignmentRecordContext._
import org.bdgenomics.formats.avro.AlignmentRecord
import org.kohsuke.args4j.{ Argument, Option => Args4jOption }
import org.bdgenomics.adam.rdd.ADAMContext._

class ViewArgs extends Args4jBase with ParquetArgs with ADAMSaveArgs {
  @Argument(required = true, metaVar = "INPUT", usage = "The ADAM, BAM or SAM file to view", index = 0)
  var inputPath: String = null

  @Argument(required = true, metaVar = "OUTPUT", usage = "Location to write output data", index = 1)
  var outputPath: String = null

  @Args4jOption(
    required = false,
    name = "-f",
    metaVar = "N",
    usage = "Restrict to reads that match all of the bits in <N>")
  var matchAllBits: Int = 0

  @Args4jOption(
    required = false,
    name = "-F",
    metaVar = "N",
    usage = "Restrict to reads that match none of the bits in <N>")
  var matchNoBits: Int = 0

}

object View extends ADAMCommandCompanion {
  val commandName = "view"
  val commandDescription = "View certain reads from an alignment-record file."

  def apply(cmdLine: Array[String]): View = {
    new View(Args4j[ViewArgs](cmdLine))
  }
}

class View(val args: ViewArgs) extends ADAMSparkCommand[ViewArgs] {
  val companion = View

  type ReadFilter = (AlignmentRecord => Boolean)

  def getFilters(n: Int, ensureValue: Boolean = true): List[ReadFilter] = {
    def getFilter(bit: Int, fn: ReadFilter): Option[ReadFilter] =
      if ((n & bit) > 0)
        Some(
          fn(_) == ensureValue
        )
      else
        None

    List(
      getFilter(0x1, _.getReadPaired),
      getFilter(0x2, _.getProperPair),
      getFilter(0x4, !_.getReadMapped),

      // NOTE(ryan): for the "mate unmapped" flag, ADAM stores the inversion of what SAM stores (i.e. "mapped" vs.
      // "unmapped"); however, they will each generally default to false if the read is not paired (in which case the
      // "mate" doesn't exist). 0x8 is only really true if the read is paired *and* the mate is not mapped; simply
      // seeing the "mate mapped" flag set to false could just be an artifact of the read not being paired, so we add
      // the extra check here and below.
      getFilter(0x8, read => read.getReadPaired && !read.getMateMapped),
      getFilter(0x10, _.getReadNegativeStrand),
      getFilter(0x20, _.getMateNegativeStrand),
      getFilter(0x40, _.getFirstOfPair),
      getFilter(0x80, _.getSecondOfPair),
      getFilter(0x100, !_.getPrimaryAlignment),
      getFilter(0x200, _.getFailedVendorQualityChecks),
      getFilter(0x400, _.getDuplicateRead),
      getFilter(0x800, _.getSupplementaryAlignment)
    ).flatten
  }

  def run(sc: SparkContext, job: Job) = {

    var reads: RDD[AlignmentRecord] = sc.adamLoad(args.inputPath)

    val filters: List[ReadFilter] = getFilters(args.matchAllBits)
    val notFilters: List[ReadFilter] = getFilters(args.matchNoBits, ensureValue = false)
    val allFilters = filters ++ notFilters

    if (allFilters.nonEmpty) {
      reads = reads.filter(read => allFilters.forall(_(read)))
    }

    reads.adamAlignedRecordSave(args)
  }
}
