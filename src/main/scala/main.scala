import music_recommandation.MusicRecommender
import my_utils.MyUtils

import scala.collection.parallel.CollectionConverters._
import scala.io._
import scala.language._

object main {
  def main(args: Array[String]): Unit = {

    // verbosity of the output (true = debugging, false = execution)
    val verbose = true

    // import train and test datasets
    def train: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("train_500_10.txt").getPath)
    def test: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("test_500_10.txt").getPath)
    if (verbose) println("Loaded files")

    // instantiate musicRecommender
    val musicRecommender: MusicRecommender = new MusicRecommender(train, test)
    if (verbose) println("MusicRecommender instanced")

    // calculating models (both sequential and parallel)
    val (
      userBasedModel: IterableOnce[(String, (String, Double))],
      userBasedModelP: IterableOnce[(String, (String, Double))],
      itemBasedModel: IterableOnce[(String, (String, Double))],
      itemBasedModelP: IterableOnce[(String, (String, Double))]
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

    // importing models from file (in case you wanna skip/separate execution wrt ubm and ibm
    val ubm = musicRecommender.importModelFromFile("models/userBasedModel.txt")
    val ubmp = musicRecommender.importModelFromFile("models/userBasedModelP.txt")
    val ibm = musicRecommender.importModelFromFile("models/itemBasedModel.txt")
    val ibmp = musicRecommender.importModelFromFile("models/itemBasedModelP.txt")

    // calculating combination models (both sequential and parallel)
    val (
      linearCombinationModel: IterableOnce[(String, (String, Double))],
      linearCombinationModelP: IterableOnce[(String, (String, Double))],
      aggregationModel: IterableOnce[(String, (String, Double))],
      aggregationModelP: IterableOnce[(String, (String, Double))],
      stochasticCombinationModel: IterableOnce[(String, (String, Double))],
      stochasticCombinationModelP: IterableOnce[(String, (String, Double))]
      ) = if (verbose) (
        MyUtils.time(musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel=false),
          "(Sequential) linear-combination model"),
        MyUtils.time(musicRecommender.getLinearCombinationModel(ubmp, ibmp, 0.5, parallel=true),
          "(Parallel) linear-combination model"),
        MyUtils.time(musicRecommender.getAggregationModel(ubm, ibm,0.5,parallel=false),
          "(Sequential) aggregation model"),
        MyUtils.time( musicRecommender.getAggregationModel(ubmp, ibmp,0.5,parallel=true),
          "(Parallel) aggregation model"),
        MyUtils.time(musicRecommender.getStochasticCombinationModel(ubm, ibm,0.5,parallel=false),
          "(Sequential) stochastic-combination model"),
        MyUtils.time(musicRecommender.getStochasticCombinationModel(ubmp, ibmp, 0.5, parallel = true),
          "(Parallel) stochastic-combination model")
      ) else (
        musicRecommender.getLinearCombinationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getLinearCombinationModel(ubmp, ibmp, 0.5, parallel = true),
        musicRecommender.getAggregationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getAggregationModel(ubmp, ibmp, 0.5, parallel = true),
        musicRecommender.getStochasticCombinationModel(ubm, ibm, 0.5, parallel = false),
        musicRecommender.getStochasticCombinationModel(ubmp, ibmp, 0.5, parallel = true)
      )

    // saving models to file (both sequential and parallel)
    musicRecommender.writeModelOnFile(linearCombinationModel, "models/linearCombinationModel.txt")
    musicRecommender.writeModelOnFile(linearCombinationModelP, "models/linearCombinationModelP.txt")
    musicRecommender.writeModelOnFile(aggregationModel, "models/aggregationModel.txt")
    musicRecommender.writeModelOnFile(aggregationModelP, "models/aggregationModelP.txt")
    musicRecommender.writeModelOnFile(stochasticCombinationModel, "models/stochasticCombinationModel.txt")
    musicRecommender.writeModelOnFile(stochasticCombinationModelP, "models/stochasticCombinationModelP.txt")
  }
}