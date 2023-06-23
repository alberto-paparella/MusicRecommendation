import my_utils.MyUtils

import java.io.{PrintWriter, Serializable}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.math.sqrt
import scala.util.Random
import scala.collection.parallel.ParSeq
import scala.collection.parallel.mutable.ParArray

object distributed extends Serializable  {

  /**
   * Store a model into a file; useful to separate the computations between user/item based models and combination models
   *
   * @param model          the model to be stored
   * @param outputFileName the path to the file the model is being stored to
   * @tparam T the internal type of the model
   */
  def writeModelOnFile[T](model: Array[T], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    model foreach{
      case seq: Seq[(String, (String, Double))] =>
        seq foreach (el2 => {
          out.write(s"${el2._1}\t${el2._2._1}\t${el2._2._2}\n")
        })
      case tuple: (String, (String, Double)) =>
        out.write(s"${tuple._1}\t${tuple._2._1}\t${tuple._2._2}\n")
    }
    out.close()
  }

  /**
   * Import a model previously stored in a file; useful to separate the computations between user/item based models
   * and the combination models
   *
   * @param pathToModel the path to the file containing the model
   * @return the model
   */
  private def importModel(pathToModel: String): Array[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)
    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.reverse)
    val model = modelFile.getLines().toArray map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }

  /**
   * The main function; computation starts here
   * @param args void
   */
  def main(args: Array[String]): Unit = {

    // verbosity of the output (true = debugging, false = execution)
    val verbose = true

    // number of train and test users (available datasets: 100_10, 100_50, 100_100, 500_10, 500_50, 500_100, ...)
    def trainUsersN: Integer = if (args.length >= 2) args(0).toInt else 100
    def testUsersN: Integer = if (args.length >= 2) args(1).toInt else 10

    if (verbose) println(s"Train users: ${trainUsersN}\nTest users: ${testUsersN}")

    // import train and test datasets
    def train: BufferedSource = Source.fromResource(s"train_${trainUsersN}_$testUsersN.txt")
    def test: BufferedSource = Source.fromResource(s"test_${trainUsersN}_$testUsersN.txt")
    def testLabelsFile: BufferedSource = Source.fromResource(s"test_labels_${trainUsersN}_$testUsersN.txt")

    if (verbose) println("Loaded files")

    // instantiate spark context
    // local execution
    // val conf = new SparkConf().setAppName("MusicRecommendation").setMaster("local[*]")
    // gcp execution
    val conf = new SparkConf().setAppName("MusicRecommendation")
    val ctx = new SparkContext(conf)

    // store all songs from both files
    val mutSongs = collection.mutable.Set[String]()
    // map song1->[{users who listened to song1}], ..., songN->[{users who listened to songN}]
    val mutSongsToUsersMap = collection.mutable.Map[String, List[String]]()
    var finalSongsToUsersMap = collection.mutable.Map[String, Array[String]]()
    /**
     * Read file containing the data storing them in their relative structures
     *
     * @param in file containing the data
     * @return list of users in the file and map of list of songs for each user
     */
    def extractData(in: BufferedSource): (Array[String], Map[String, Array[String]]) = {
      // all users in file
      val usersInFile = collection.mutable.Set[String]()
      // map user1->[{songs listened by user1}], ..., userN->[{songs listened by userN}]
      val mutUsersToSongsMap = collection.mutable.Map[String, List[String]]()
      // for each split line on "\t"
      for {
        line <- in.getLines().toList
      } yield line split "\t" match {
        case Array(u, s, _) =>
          // add user and song
          usersInFile add u
          mutSongs add s
          // update maps
          mutUsersToSongsMap.update(u, s :: mutUsersToSongsMap.getOrElse(u, Nil))
          mutSongsToUsersMap.update(s, u :: mutSongsToUsersMap.getOrElse(s, Nil))
      }
      val usersToSongsMap: collection.mutable.Map[String, Array[String]] = mutUsersToSongsMap.map {
        case (k, v) => k -> v.toArray
      }

      finalSongsToUsersMap = mutSongsToUsersMap.map {
        case (k, v) => k -> v.toArray
      }

      (usersInFile.toArray, usersToSongsMap.toMap)
    }

    // get train and test data from files
    val (trainUsers, trainUsersToSongsMap) = extractData(train)
    val (testUsers, testUsersToSongsMap) = extractData(test)
    // convert mutable to immutable list
    val songs: Array[String] = mutSongs.toArray
    // convert mutable to immutable map
    val songsToUsersMap: Map[String, Array[String]] = finalSongsToUsersMap.toMap

    val slicesRatio: Integer = 100
    val numberSlices: Integer = songs.length / slicesRatio

    if (verbose) println("Songs: " + songs.length)

    /**
     * Import data from the test labels file used for evaluation
     *
     * @param in file containing the test labels data
     * @return a map representing for each test user the latter half of its music history (i.e., the ground truth)
     */
    def importTestLabels(in: BufferedSource): (Map[String, Array[String]], Array[String]) = {
      val mutTestLabels = collection.mutable.Map[String, List[String]]()
      val newSongs = collection.mutable.Set[String]()
      // for each split line on "\t"
      for {
        line <- in.getLines().toList
      } yield line split "\t" match {
        case Array(u, s, _) =>
          // users are the same as in testUsers, while testLabels could contain new songs
          newSongs add s
          // update map
          mutTestLabels.update(u, s :: mutTestLabels.getOrElse(u, Nil))
      }
      val testLabels: Map[String, Array[String]] = mutTestLabels.map {
        case (key, value) => key -> value.toArray
      }.toMap
      (testLabels, newSongs.toArray)
    }

    // validation data (import just once at initialization)
    val (testLabels, newSongs) = importTestLabels(testLabelsFile)
    // parallelized here because SparkContext cannot be serialized (e.g. inside averagePrecision function)
    val newSongsRdd = ctx.parallelize(newSongs, newSongs.length/slicesRatio)

    if (verbose) println("New songs: " + newSongs.length)

    /**
     * ******************************************************************************************
     * OBJECTS FOR COMPUTATION DISTRIBUTION
     * ******************************************************************************************
     */

    /**
     * User based model functions
     */
    object UserBasedModel {
      /**
       * Get the cosine similarity between two users
       *
       * @param user1 the first user (presumably from test users)
       * @param user2 the second user (presumably from trains users)
       * @return the cosine similarity between the two users
       */
      def cosineSimilarity(user1: String, user2: String): Double = {
        // number of songs listened by both users
        val numerator = songs.map(song =>
          // if both users listened to song return 1, else 0
          if (testUsersToSongsMap(user1).contains(song) && trainUsersToSongsMap(user2).contains(song)) 1 else 0
        ).sum
        // _usersToSongMap(user).length represents how many songs the user listened to
        val denominator = sqrt(testUsersToSongsMap(user1).length) * sqrt(trainUsersToSongsMap(user2).length)
        if (denominator != 0) numerator / denominator else 0.0
      }

      /**
       * Calculate the rank for each song for the user (i.e., the sum of the cosine similarities with the users in train
       * users who listened to the song)
       *
       * @param user the user
       * @return the rank for each song for the user
       */
      def getRanks1(user: String): ParArray[(String, (String, Double))] = {
        // foreach song, calculate the score for the user
        for {
          song <- songs.par filter (song => !testUsersToSongsMap(user).contains(song))
        } yield {
          user -> (song, rank(user, song))
        }
      }

      /**
       * Calculate the rank for the song for each user (i.e., the sum of the cosine similarities with the users in train
       * users who listened to the song)
       *
       * @param song the song
       * @return the rank for the song for each user
       */
      def getRanks2(song: String): ParArray[(String, (String, Double))] = {
        // foreach user, calculate the score for the user
        for {
          user <- testUsers.par filter (user => !testUsersToSongsMap(user).contains(song))
        } yield {
          user -> (song, rank(user, song))
        }
      }

      /**
       * Calculate the rank of the song for the user (i.e., the sum of the cosine similarities with the users in train
       * users who listened to the song)
       *
       * @param user the user
       * @param song the song
       * @return the rank of the song for the user
       */
      def rank(user: String, song: String): Double = {
        // sum the cosine similarities between the user and each user in the training set that listened to the song
        for {
          user2 <- trainUsers filter (user2 => trainUsersToSongsMap(user2).contains(song))
        } yield {
          cosineSimilarity(user, user2)
        }
      } sum
    }

    /**
     * Item based model functions
     */
    object ItemBasedModel {
      /**
       * Get the cosine similarity between two songs
       *
       * @param song1 the first song
       * @param song2 the second song
       * @return the cosine similarity between the two songs
       */
      def cosineSimilarity(song1: String, song2: String): Double = {
        // number of users who listened to both songs
        val numerator = trainUsers.map(user =>
          // if the user listened to both songs return 1, else 0
          if (songsToUsersMap(song1).contains(user) && songsToUsersMap(song2).contains(user)) 1 else 0).sum
        // pre-calculate denominator to catch if it is equal to 0
        val denominator = sqrt(songsToUsersMap(song1).length) * sqrt(songsToUsersMap(song2).length)
        if (denominator != 0) numerator / denominator else 0
      }

      /**
       * Calculate the rank for each song for the user (i.e., the sum of the cosine similarities of the songs listened by
       * the user with the other songs)
       *
       * @param user the user
       * @return the rank for each song for the user
       */
      def getRanks1(user: String): ParArray[(String, (String, Double))] = {
        // foreach song, calculate the score for the user
        for {
          s <- songs.par filter (s => !testUsersToSongsMap(user).contains(s))
        } yield {
          user -> (s, rank(user, s))
        }
      }

      /**
       * Calculate the rank for the song for each user (i.e., the sum of the cosine similarities of the songs listened by
       * the user with the other songs)
       *
       * @param song the song
       * @return the rank for the song for each user
       */
      def getRanks2(song: String): ParArray[(String, (String, Double))] = {
        // foreach user, calculate the score for the user
        for {
          user <- testUsers.par filter (user => !testUsersToSongsMap(user).contains(song))
        } yield {
          user -> (song, rank(user, song))
        }
      }

      /**
       * Calculate the rank of the song for the user (i.e., the sum of the cosine similarities of the songs listened by
       * the user with the other songs)
       *
       * @param user the user
       * @param song the song
       * @return the rank for each song for each user
       */
      def rank(user: String, song: String): Double = {
        // sum the cosine similarities between the song and each other song the user listened to
        for {
          song2 <- songs filter (song2 => song2 != song && songsToUsersMap(song2).contains(user))
        } yield {
          cosineSimilarity(song, song2)
        }
      } sum
    }

    /**
     * Functions for the evaluation of the models
     */
    object EvaluationFunctions extends Serializable {
      /**
       * Convert the prediction scores to class labels
       *
       * @param model     the predictions scores for test users
       * @param threshold _ > threshold = 1, _ <= threshold = 0 (default 0.0)
       * @return map of list of songs the user will listen predicted by the model for each user
       */
      def predictionToClassLabels(model: Array[(String,(String, Double))], threshold: Double = 0.0): Map[String, Array[String]] = {
        // model contains the prediction scores for test users
        val predictions = collection.mutable.Map[String, List[String]]()
        val min = model.map(el => el._2._2).min
        val max = model.map(el => el._2._2).max

        model foreach (el => {
          // values can be greater than 1, therefore normalization is advised
          if ((el._2._2 - min) / (max - min) > threshold) predictions.update(el._1, el._2._1 :: predictions.getOrElse(el._1, Nil))
        })

        val preds: collection.mutable.Map[String, Array[String]] = predictions.map {
          case (k, v) => k -> v.toArray
        }

        preds.toMap
      }

      /**
       * Calculate the confusion matrix wrt a specific class given the predictions provided by the model
       *
       * @param predictions the predictions provided by the model given a specific threshold
       * @param song        the class over we are calculating the confusion matrix
       * @return the confusion matrix for a specific class song
       */
      def confusionMatrix(predictions: Map[String, Array[String]], song: String): (Int, Int, Int, Int) = {
        // NB: if threshold too high, user could not be in predictions!
        testUsers.map(user => (
          // True positives
          if (predictions.contains(user) && predictions(user).contains(song) && testLabels(user).contains(song)) 1 else 0,
          // False positives
          if (predictions.contains(user) && predictions(user).contains(song) && !testLabels(user).contains(song)) 1 else 0,
          // True negatives
          if ((!predictions.contains(user) || !predictions(user).contains(song)) && !testLabels(user).contains(song)) 1 else 0,
          // False negatives
          if ((!predictions.contains(user) || !predictions(user).contains(song)) && testLabels(user).contains(song)) 1 else 0
        )).fold((0, 0, 0, 0)) { (acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3, acc._4 + tup._4) }
      }

      /**
       * Calculate precision = TP / (TP + FP)
       *
       * @param confusionMatrix the confusion matrix wrt to the model using a specific threshold wrt the testLabels
       * @return the precision of the model for a specific class
       */
      def precision(confusionMatrix: (Int, Int, Int, Int)): Double = {
        if (confusionMatrix._1 + confusionMatrix._2 > 0.0)
          confusionMatrix._1.toDouble / (confusionMatrix._1 + confusionMatrix._2)
        else
          0.0
      }

      /**
       * Calculate recall = TP / (TP + FN)
       *
       * @param confusionMatrix the confusion matrix wrt to the model using a specific threshold wrt the testLabels
       * @return the recall of the model for a specific class
       */
      def recall(confusionMatrix: (Int, Int, Int, Int)): Double = {
        if (confusionMatrix._1 + confusionMatrix._4 > 0.0)
          confusionMatrix._1.toDouble / (confusionMatrix._1 + confusionMatrix._4)
        else
          0.0
      }

      /**
       * Calculate the average precision of the model for each class (i.e., each song)
       *
       * @param model the model to be evaluated
       * @return a list containing the average precisions for each class (i.e., each song)
       */
      def averagePrecision(model: Array[(String, (String, Double))]): RDD[(String, Double)] = {
        val thresholds: Array[Double] = Array(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
        // predictions = (threshold) -> (user -> list of songs)
        val predictions = for {t <- thresholds} yield {
          predictionToClassLabels(model, t)
        }

        /**
         * Calculate the average precision of the model for a single class
         *
         * @param song the class wrt the model is being evaluated
         * @return the average precision of the model for the class song
         */
        def singleAveragePrecision(song: String): Double = {
          thresholds.zipWithIndex.map(t =>
            if (t._2 == (thresholds.length - 1)) {
              0.0
            }
            else if (t._2 == (thresholds.length - 2)) {
              (recall(confusionMatrix(predictions(t._2), song)) - 0.0) * precision(confusionMatrix(predictions(t._2), song))
            } else
              (recall(confusionMatrix(predictions(t._2), song)) - recall(confusionMatrix(predictions(t._2 + 1), song))) * precision(confusionMatrix(predictions(t._2), song))
          ).sum
        }

         newSongsRdd map (song => (song, singleAveragePrecision(song)))
      }

      /**
       * Calculate the mean Average Precision (mAP) of the given model
       *
       * @param model the model to be evaluated
       * @return the mAP of the model
       */
      def meanAveragePrecision(model: Array[(String, (String, Double))]): Double = {
        //averagePrecision(model).collect().map(ap => ap._2).sum / newSongs.length
        //averagePrecision(model).collect().map(ap => ap._2).sum / newSongs.length
        averagePrecision(model).map(ap => ap._2).sum / newSongs.length
      }

      /**
       * Evaluate the model using mAP
       *
       * @param model the model to be evaluated
       * @return the mAP of the model
       */
      def evaluateModel(model: Array[(String, (String, Double))]): Double = {
        // @model contains the prediction scores for test users
        meanAveragePrecision(model)
      }
    }

    /**
     * Calculate user based model. Version distributing over users (internal parallelization over songs)
     *
     * @return user based model
     */
    def getUserBasedModel1: Array[(String, (String, Double))] = {
      ctx.parallelize(testUsers, slicesRatio).map(user => UserBasedModel.getRanks1(user).seq).collect.flatten
    }

    /**
     * Calculate user based model. Version distributing over songs (internal parallelization over users)
     *
     * @return user based model
     */
    def getUserBasedModel2: Array[(String, (String, Double))] = {
      ctx.parallelize(songs, slicesRatio).map(song => UserBasedModel.getRanks2(song).seq).collect.flatten
    }

    /**
     * Calculate item based model. Version distributing over users (internal parallelization over songs)
     *
     * @return item based model
     */
    def getItemBasedModel1: Array[(String, (String, Double))] = {
      ctx.parallelize(testUsers, slicesRatio).map(user => ItemBasedModel.getRanks1(user).seq).collect.flatten
    }

    /**
     * Calculate item based model. Version distributing over songs (internal parallelization over users)
     *
     * @return item based model
     */
    def getItemBasedModel2: Array[(String, (String, Double))] = {
      ctx.parallelize(songs, slicesRatio).map(song => ItemBasedModel.getRanks2(song).seq).collect.flatten
    }

    /**
     * Calculate linear combination model given the user and the item based models and an alpha parameter
     *
     * @param ubm      the user based model
     * @param ibm      the item based model
     * @param alpha    weight of the contribute of the user based model; ibm will be weighted accordingly (lcAlpha - 1)
     * @return the linear combination model
     */
    def getLinearCombinationModel(ubm: Array[(String, (String, Double))],
                                  ibm: Array[(String, (String, Double))],
                                  alpha: Double = 0.5): Array[(String, (String, Double))] = {

      ctx.parallelize(ubm.zip(ibm), slicesRatio) map {
        // for each pair
        case ((user1, (song1, rank1)), (user2, (song2, rank2))) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          user1 -> (song1, rank1 * alpha + rank2 * (1 - alpha))
      } collect()
    }

    /**
     * Calculate aggregation model given the user and the item based models and an itemBasedPercentage parameter
     *
     * @param ubm                 the user based model
     * @param ibm                 the item based model
     * @param itemBasedPercentage how many ranks are taken from ubm and ibm
     * @return the aggregation model
     */
    def getAggregationModel(ubm: Array[(String, (String, Double))],
                            ibm: Array[(String, (String, Double))],
                            itemBasedPercentage: Double = 0.5): Array[(String, (String, Double))] = {

      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      ctx.parallelize(ubm.zip(ibm).zipWithIndex, slicesRatio) map{
        // for each pair
        case (couple, index) => couple match {
          case ((user1, (song1, rank1)), (user2, (song2, rank2))) =>
            if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1))
        }
      } collect()
    }

    /**
     * Calculate stochastic combination model given the user and the item based models and an itemBasedProbability parameter
     *
     * @param ubm                  the user based model
     * @param ibm                  the item based model
     * @param itemBasedProbability threshold over which ranks are chosen from ibm model
     * @return the stochastic combination model
     */
    def getStochasticModel(ubm: Array[(String, (String, Double))],
                           ibm: Array[(String, (String, Double))],
                           itemBasedProbability: Double = 0.5): Array[(String, (String, Double))] = {
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      ctx.parallelize(ubm.zip(ibm), slicesRatio) map {
        // for each pair
        case ((user1, (song1, rank1)), (user2, (song2, rank2))) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      } collect()
    }

    // songs >> users, nodes << cores per node (or songs << users, nodes >> cores per node)
    val ubModel = MyUtils.time(getUserBasedModel1, "(Distributed) user-based1")
    val ibModel = MyUtils.time(getItemBasedModel1, "(Distributed) item-based1")

    // songs >> users, nodes >> cores per node (or songs << users, nodes << cores per node)
    val ubModel2 = MyUtils.time(getUserBasedModel2, "(Distributed) user-based2")
    val ibModel2 = MyUtils.time(getItemBasedModel2, "(Distributed) item-based2")

    /*
    // saving models to file
    writeModelOnFile(ubModel, "models/userBasedModelD.txt")
    writeModelOnFile(ibModel, "models/itemBasedModelD.txt")

    // importing models from file
    val ubm = importModel("models/userBasedModelD.txt")
    val ibm = importModel("models/itemBasedModelD.txt")
     */

    // ordering user/item based models is needed to make use of zipWithIndex in combination models
    val ordering = Ordering.Tuple2(Ordering.String, Ordering.Tuple2(Ordering.String, Ordering.Double.reverse))
    val ubm = ubModel sorted ordering
    val ibm = ibModel sorted ordering

    // calculating combination models
    val lcModel = MyUtils.time(getLinearCombinationModel(ubm, ibm), "(Distributed) linear combination")
    val aModel = MyUtils.time(getAggregationModel(ubm, ibm), "(Distributed) aggregation model")
    val scModel = MyUtils.time(getStochasticModel(ubm, ibm), "(Distributed) stochastic combination model")

    // saving models to file
    /*
    writeModelOnFile(ubModel, "models/userBasedModelD.txt")
    writeModelOnFile(ibModel, "models/itemBasedModelD.txt")
    writeModelOnFile(lcModel, "models/linearCombinationModelD.txt")
    writeModelOnFile(aModel, "models/aggregationModelD.txt")
    writeModelOnFile(scModel, "models/stochasticCombinationModelD.txt")
     */

    // evaluating models; mAP should be the same wrt seq and par models
    val mAP = (
      MyUtils.time(EvaluationFunctions.evaluateModel(ubModel), "(Distributed) user-based model mAP"),
      MyUtils.time(EvaluationFunctions.evaluateModel(ibModel), "(Distributed) item-based model mAP"),
      MyUtils.time(EvaluationFunctions.evaluateModel(lcModel), "(Distributed) linear-combination model mAP"),
      MyUtils.time(EvaluationFunctions.evaluateModel(aModel),  "(Distributed) aggregation model mAP"),
      MyUtils.time(EvaluationFunctions.evaluateModel(scModel), "(Distributed) stochastic-combination model mAP")
     )
    println("(Distributed) user-based model mAP: " + MyUtils.roundAt(10, mAP._1))
    println("(Distributed) item-based model mAP: " + MyUtils.roundAt(10, mAP._2))
    println("(Distributed) linear-combination model mAP: " + MyUtils.roundAt(10, mAP._3))
    println("(Distributed) aggregation model model mAP: " + MyUtils.roundAt(10, mAP._4))
    println("(Distributed) stochastic-combination model mAP: " + MyUtils.roundAt(10, mAP._5))
  }
}
