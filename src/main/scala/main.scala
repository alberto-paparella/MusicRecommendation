import music_recommandation.MusicRecommender
import my_utils.MyUtils

import scala.collection.parallel.mutable.ParArray
import scala.io._
import scala.language._

object main {
  def main(args: Array[String]): Unit = {

    // verbosity of the output (true = debugging, false = execution)
    val verbose = true

    // number of train and test users (available datasets: 100_10, 100_50, 100_100, 500_10, 500_50, 500_100, ...)
    def trainUsersN: Integer = if(args.length >= 2) args(0).toInt else 100
    def testUsersN: Integer = if(args.length >= 2) args(1).toInt else 10

    if(verbose) println(s"Train users: $trainUsersN\nTest users: $testUsersN")

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
      userBasedModel: Array[(String, (String, Double))],
      userBasedModelP: ParArray[(String, (String, Double))],
      itemBasedModel: Array[(String, (String, Double))],
      itemBasedModelP: ParArray[(String, (String, Double))]
      ) = if (verbose) (
        MyUtils.time(musicRecommender.getUserBasedModel, "(Sequential) user-based model"),
        MyUtils.time(musicRecommender.getUserBasedModelP, "(Parallel) user-based model"),
        MyUtils.time(musicRecommender.getItemBasedModel, "(Sequential) item-based model"),
        MyUtils.time(musicRecommender.getItemBasedModelP, "(Parallel) item-based model")
      ) else (
        musicRecommender.getUserBasedModel,
        musicRecommender.getUserBasedModelP,
        musicRecommender.getItemBasedModel,
        musicRecommender.getItemBasedModelP
      )

    // saving models to file (both sequential and parallel)
    //musicRecommender.writeModelOnFile(userBasedModel, "models/userBasedModel.txt")
    //musicRecommender.writeModelOnFile(userBasedModelP, "models/userBasedModelP.txt")
    //musicRecommender.writeModelOnFile(itemBasedModel, "models/itemBasedModel.txt")
    //musicRecommender.writeModelOnFile(itemBasedModelP, "models/itemBasedModelP.txt")

    // importing models from file (in case you wanna skip/separate execution wrt ubm and ibm)
    //val ubm = musicRecommender.importModelFromFile("models/userBasedModel.txt")
    //val ibm = musicRecommender.importModelFromFile("models/itemBasedModel.txt")
    val ordering = Ordering.Tuple2(Ordering.String, Ordering.Tuple2(Ordering.String, Ordering.Double.reverse))
    val ubm = userBasedModel sorted ordering
    val ibm = itemBasedModel sorted ordering

    // calculating combination models (both sequential and parallel)
    val (
      linearCombinationModel: Array[(String, (String, Double))],
      linearCombinationModelP: ParArray[(String, (String, Double))],
      aggregationModel: Array[(String, (String, Double))],
      aggregationModelP: ParArray[(String, (String, Double))],
      stochasticCombinationModel: Array[(String, (String, Double))],
      stochasticCombinationModelP: ParArray[(String, (String, Double))]
      ) = if (verbose) (
        MyUtils.time(musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5),
          "(Sequential) linear-combination model"),
        MyUtils.time(musicRecommender.getLinearCombinationModelP(ubm, ibm, 0.5),
          "(Parallel) linear-combination model"),
        MyUtils.time(musicRecommender.getAggregationModel(ubm, ibm,0.5),
          "(Sequential) aggregation model"),
        MyUtils.time( musicRecommender.getAggregationModelP(ubm, ibm,0.5),
          "(Parallel) aggregation model"),
        MyUtils.time(musicRecommender.getStochasticCombinationModel(ubm, ibm,0.5),
          "(Sequential) stochastic-combination model"),
        MyUtils.time(musicRecommender.getStochasticCombinationModelP(ubm, ibm, 0.5),
          "(Parallel) stochastic-combination model")
      ) else (
        musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5),
        musicRecommender.getLinearCombinationModelP(ubm, ibm, 0.5),
        musicRecommender.getAggregationModel(ubm, ibm, 0.5),
        musicRecommender.getAggregationModelP(ubm, ibm, 0.5),
        musicRecommender.getStochasticCombinationModel(ubm, ibm, 0.5),
        musicRecommender.getStochasticCombinationModelP(ubm, ibm, 0.5)
      )

    // saving models to file (both sequential and parallel)
    //musicRecommender.writeModelOnFile(linearCombinationModel, "models/linearCombinationModel.txt")
    //musicRecommender.writeModelOnFile(linearCombinationModelP, "models/linearCombinationModelP.txt")
    //musicRecommender.writeModelOnFile(aggregationModel, "models/aggregationModel.txt")
    //musicRecommender.writeModelOnFile(aggregationModelP, "models/aggregationModelP.txt")
    //musicRecommender.writeModelOnFile(stochasticCombinationModel, "models/stochasticCombinationModel.txt")
    //musicRecommender.writeModelOnFile(stochasticCombinationModelP, "models/stochasticCombinationModelP.txt")

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
    println("(Sequential) user-based model mAP: " + MyUtils.roundAt(10, mAP._1))
    println("(Parallel) user-based model mAP: " + MyUtils.roundAt(10, mAP._2))
    println("(Sequential) item-based model mAP: " + MyUtils.roundAt(10, mAP._3))
    println("(Parallel) item-based model mAP: " + MyUtils.roundAt(10, mAP._4))
    println("(Sequential) linear-combination model mAP: " + MyUtils.roundAt(10, mAP._5))
    println("(Parallel) linear-combination model mAP: " + MyUtils.roundAt(10, mAP._6))
    println("(Sequential) aggregation model model mAP: " + MyUtils.roundAt(10, mAP._7))
    println("(Parallel) aggregation model mAP: " + MyUtils.roundAt(10, mAP._8))
    println("(Sequential) stochastic-combination model mAP: " + MyUtils.roundAt(10, mAP._9))
    println("(Parallel) stochastic-combination model mAP: " + MyUtils.roundAt(10, mAP._10))

  }
}