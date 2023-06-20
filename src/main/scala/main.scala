import music_recommandation.MusicRecommender
import my_utils.MyUtils

import scala.collection.GenSeq
import scala.io._
import scala.language._

object main {
  def main(args: Array[String]): Unit = {

    // verbosity of the output (true = debugging, false = execution)
    val verbose = true

    // number of train and test users (available datasets: 100_10, 100_50, 100_100, 500_10, 500_50, 500_100, ...)
    def trainUsersN: Integer = 300
    def testUsersN: Integer = 10

    // import train and test datasets
    def train: BufferedSource = Source.fromResource(s"train_${trainUsersN}_$testUsersN.txt")
    def test: BufferedSource = Source.fromResource(s"test_${trainUsersN}_$testUsersN.txt")
    def testLabels: BufferedSource = Source.fromResource(s"test_labels_${trainUsersN}_$testUsersN.txt")
    if (verbose) println("Loaded files")

    // instantiate musicRecommender
    val musicRecommender: MusicRecommender = new MusicRecommender(train, test, testLabels)
    if (verbose) println("MusicRecommender instanced")

    // calculating models (both sequential and parallel)
    val (
      userBasedModel: GenSeq[(String, (String, Double))],
      userBasedModelP: GenSeq[(String, (String, Double))],
      itemBasedModel: GenSeq[(String, (String, Double))],
      itemBasedModelP: GenSeq[(String, (String, Double))]
      ) = if (verbose) (
        MyUtils.time(musicRecommender.getUserBasedModel(parallel=false), "(Sequential) user-based model"),
        MyUtils.time(musicRecommender.getUserBasedModel(parallel=true), "(Parallel) user-based model"),
        MyUtils.time(musicRecommender.getItemBasedModel(parallel = false), "(Sequential) item-based model"),
        MyUtils.time(musicRecommender.getItemBasedModel(parallel = true), "(Parallel) item-based model")
      ) else (
        musicRecommender.getUserBasedModel(parallel=false),
        musicRecommender.getUserBasedModel(parallel=true),
        musicRecommender.getItemBasedModel(parallel = false),
        musicRecommender.getItemBasedModel(parallel = true)
      )

    // saving models to file (both sequential and parallel)
    musicRecommender.writeModelOnFile(userBasedModel, "models/userBasedModel.txt")
    musicRecommender.writeModelOnFile(userBasedModelP, "models/userBasedModelP.txt")
    musicRecommender.writeModelOnFile(itemBasedModel, "models/itemBasedModel.txt")
    musicRecommender.writeModelOnFile(itemBasedModelP, "models/itemBasedModelP.txt")

    // importing models from file (in case you wanna skip/separate execution wrt ubm and ibm)
    val ubm = musicRecommender.importModelFromFile("models/userBasedModel.txt")
    val ibm = musicRecommender.importModelFromFile("models/itemBasedModel.txt")

    // calculating combination models (both sequential and parallel)
    val (
      linearCombinationModel: GenSeq[(String, (String, Double))],
      linearCombinationModelP: GenSeq[(String, (String, Double))],
      aggregationModel: GenSeq[(String, (String, Double))],
      aggregationModelP: GenSeq[(String, (String, Double))],
      stochasticCombinationModel: GenSeq[(String, (String, Double))],
      stochasticCombinationModelP: GenSeq[(String, (String, Double))]
      ) = if (verbose) (
        MyUtils.time(musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel=false),
          "(Sequential) linear-combination model"),
        MyUtils.time(musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel=true),
          "(Parallel) linear-combination model"),
        MyUtils.time(musicRecommender.getAggregationModel(ubm, ibm,0.5,parallel=false),
          "(Sequential) aggregation model"),
        MyUtils.time( musicRecommender.getAggregationModel(ubm, ibm,0.5,parallel=true),
          "(Parallel) aggregation model"),
        MyUtils.time(musicRecommender.getStochasticCombinationModel(ubm, ibm,0.5,parallel=false),
          "(Sequential) stochastic-combination model"),
        MyUtils.time(musicRecommender.getStochasticCombinationModel(ubm, ibm, 0.5, parallel = true),
          "(Parallel) stochastic-combination model")
      ) else (
        musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel = true),
        musicRecommender.getAggregationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getAggregationModel(ubm, ibm, 0.5, parallel = true),
        musicRecommender.getStochasticCombinationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getStochasticCombinationModel(ubm, ibm, 0.5, parallel = true)
      )

    // saving models to file (both sequential and parallel)
    musicRecommender.writeModelOnFile(linearCombinationModel, "models/linearCombinationModel.txt")
    musicRecommender.writeModelOnFile(linearCombinationModelP, "models/linearCombinationModelP.txt")
    musicRecommender.writeModelOnFile(aggregationModel, "models/aggregationModel.txt")
    musicRecommender.writeModelOnFile(aggregationModelP, "models/aggregationModelP.txt")
    musicRecommender.writeModelOnFile(stochasticCombinationModel, "models/stochasticCombinationModel.txt")
    musicRecommender.writeModelOnFile(stochasticCombinationModelP, "models/stochasticCombinationModelP.txt")

    // evaluating models; mAP should be the same between sequential and parallel, except for stochasticCombinationModel
    val mAP = (
      MyUtils.time(musicRecommender.evaluateModel(userBasedModel),"(Sequential) user-based model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(userBasedModelP, parallel=true),"(Parallel) user-based model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(itemBasedModel),"(Sequential) item-based model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(itemBasedModelP, parallel=true),"(Parallel) item-based model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(linearCombinationModel),"(Sequential) linear-combination model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(linearCombinationModelP, parallel=true),"(Parallel) linear-combination model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(aggregationModel),"(Sequential) aggregation model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(aggregationModelP, parallel=true),"(Parallel) aggregation model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(stochasticCombinationModel),"(Sequential) stochastic-combination model mAP"),
      MyUtils.time(musicRecommender.evaluateModel(stochasticCombinationModelP, parallel=true),"(Parallel) stochastic-combination model mAP")
    )
    println("(Sequential) user-based model mAP: " + mAP._1)
    println("(Parallel) user-based model mAP: " + mAP._2)
    println("(Sequential) item-based model mAP: " + mAP._3)
    println("(Parallel) item-based model mAP: " + mAP._4)
    println("(Sequential) linear-combination model mAP: " + mAP._5)
    println("(Parallel) linear-combination model mAP: " + mAP._6)
    println("(Sequential) aggregation model model mAP: " + mAP._7)
    println("(Parallel) aggregation model mAP: " + mAP._8)
    println("(Sequential) stochastic-combination model mAP: " + mAP._9)
    println("(Parallel) stochastic-combination model mAP: " + mAP._10)

  }
}