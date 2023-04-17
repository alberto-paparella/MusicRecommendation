import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import java.io.PrintWriter
import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.math.sqrt
import scala.util.Random

import my_utils.MyUtils

object distributed extends Serializable {

  def writeModelOnFile[T](model: Array[T], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    // we are printing to a file; therefore, parallelization would not improve performances
    model foreach({
      case seq: Seq[(String, (String, Double))] => seq foreach (el2 => {
        out.write(s"${el2._1}\t${el2._2._1}\t${el2._2._2}\n")
      })
      case tuple: (String, (String, Double)) =>
        out.write(s"${tuple._1}\t${tuple._2._1}\t${tuple._2._2}\n")
    })
    out.close()
  }

  private def importModel(pathToModel: String): Seq[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)

    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.reverse)
    val model = modelFile.getLines().toList map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }
  def main(args: Array[String]): Unit = {

    // import train and test datasets
    def train: BufferedSource = Source.fromFile("/media/alberto/5cc4e6c6-e71d-42b1-b6ec-bdfab5eabe1c/unibo/scp/MusicReccomendation/src/main/resources/train_100_10.txt")
    def test: BufferedSource = Source.fromFile("/media/alberto/5cc4e6c6-e71d-42b1-b6ec-bdfab5eabe1c/unibo/scp/MusicReccomendation/src/main/resources/test_100_10.txt")
    def testLabelsFile: BufferedSource = Source.fromFile("/media/alberto/5cc4e6c6-e71d-42b1-b6ec-bdfab5eabe1c/unibo/scp/MusicReccomendation/src/main/resources/test_labels_100_10.txt")

    // instantiate spark context
    val conf = new SparkConf().setAppName("MusicRecommendation").setMaster("local[*]")
    val ctx = new SparkContext(conf)

    // store all songs from both files
    val mutSongs = collection.mutable.Set[String]()
    // map song1->[{users who listened to song1}], ..., songN->[{users who listened to songN}]
    val mutSongsToUsersMap = collection.mutable.Map[String, List[String]]()

    def extractData(in: BufferedSource): (Seq[String], Map[String, List[String]]) = {
      // all users in file
      val usersInFile = collection.mutable.Set[String]()
      // map user1->[{songs listened by user1}], ..., userN->[{songs listened by userN}]
      val usersToSongsMap = collection.mutable.Map[String, List[String]]()
      // for each split line on "\t"
      for {
        line <- in.getLines().toList
      } yield line split "\t" match {
        case Array(u, s, _) => {
          // add user and song
          usersInFile add u
          mutSongs add s
          // update maps
          usersToSongsMap.update(u, s :: usersToSongsMap.getOrElse(u, Nil))
          mutSongsToUsersMap.update(s, u :: mutSongsToUsersMap.getOrElse(s, Nil))
        }
      }
      (usersInFile.toSeq, usersToSongsMap.toMap)
    }

    // get train and test data from files
    val (trainUsers, trainUsersToSongsMap) = extractData(train)
    val (testUsersList, testUsersToSongsMap) = extractData(test)
    // convert mutable to immutable list
    val songsList: Seq[String] = mutSongs.toSeq
    // convert mutable to immutable map
    val songsToUsersMap = mutSongsToUsersMap.toMap

    def importTestLabels(in: BufferedSource): (Map[String, List[String]], List[String]) = {
      val testLabels = collection.mutable.Map[String, List[String]]()
      val newSongs = collection.mutable.Set[String]()
      // for each split line on "\t"
      for {
        line <- in.getLines().toList
      } yield line split "\t" match {
        case Array(u, s, _) => {
          // users are the same as in testUsers, while testLabels could contain new songs
          newSongs add s
          // update map
          testLabels.update(u, s :: testLabels.getOrElse(u, Nil))
        }
      }
      (testLabels.toMap, newSongs.toList)
    }

    // validation data (import just once at initialization)
    val (testLabels, newSongs) = importTestLabels(testLabelsFile)

    // create users and songs RDD
    /*
    NOTE: you cannot map a RDD inside a transformation of another RDD (e.g. users.map(u=> songs.map(s=> ...))
    That's why we need usersList, songsList(:Seq[String]) AND users, songs(:RDD[String])
    */
    val testUsers = ctx.parallelize(testUsersList)
    val songs = ctx.parallelize(songsList)

    object ubmFunctions {
      // it calculates the cosine similarity between two users
      def cosineSimilarity(user1: String, user2: String) = {
        // Here, parallelization does not improve performances (TODO: check)
        val numerator = songsList.map(song =>
          // if both users listened to song return 1, else 0
          if (testUsersToSongsMap(user1).contains(song) && trainUsersToSongsMap(user2).contains(song)) 1 else 0
        ).sum
        // usersToSongMap(user).length represents how many songs the user listened to
        val denominator = sqrt(testUsersToSongsMap(user1).length) * sqrt(trainUsersToSongsMap(user2).length)
        if (denominator != 0) numerator / denominator else 0.0
      }

      def getModel(user: String) = {
        // foreach song, calculate the score for the user
        songsList.map(s => user -> (s, rank(user, s)))
      }

      def getModel2(song: String) = {
        // foreach song, calculate the score for the user
        testUsersList.map(u => u -> (song, rank(u, song)))
      }

      def rank (user: String, song: String) = {
        for {
          u2 <- trainUsers filter (u => u != user && trainUsersToSongsMap(u).contains(song))
        } yield {
          cosineSimilarity(user, u2)
        }
      } sum

    }

    object ibmFunctions {
      // it calculates the cosine similarity between two songs
      def cosineSimilarity(song1: String, song2: String): Double = {
        // Here, parallelization does not improve performances (TODO: check)
        val numerator = trainUsers.map(user => (
          // if the user listened to both songs return 1, else 0
          if (songsToUsersMap(song1).contains(user) && songsToUsersMap(song2).contains(user)) 1 else 0
          )).sum
        // pre-calculate denominator to catch if it is equal to 0
        val denominator = sqrt(songsToUsersMap(song1).length) * sqrt(songsToUsersMap(song2).length)
        if (denominator != 0) numerator / denominator else 0
      }

      def rank(user: String, song: String): Double = {
        // Here, parallelization does not improve performances (TODO: check)
        for {
          s2 <- songsList filter(s => s != song && songsToUsersMap(s).contains(user))
        } yield {
          cosineSimilarity(song, s2)
        }
      } sum

      def getModel(song: String) = {
        // foreach song, calculate the score for the user
        testUsersList.map(u => u -> (song, rank(u, song)))
      }

      def getModel2(user: String) = {
        // foreach song, calculate the score for the user
        songsList.map(s => user -> (s, rank(user, s)))
      }
    }

    object evaluation_functions {
      /**
       * Convert the prediction scores to class labels
       *
       * @param model     the predictions scores for test users
       * @param threshold _ > threshold = 1, _ <= threshold = 0 (default 0.0)
       * @return map of list of songs the user will listen predicted by the model for each user
       */
      def predictionToClassLabels(model: Array[(String,(String, Double))], threshold: Double = 0.0): Map[String, List[String]] = {
        // @model contains the prediction scores for test users
        val predictions = collection.mutable.Map[String, List[String]]()
        val min = model.map(el => (el._2._2)).min
        val max = model.map(el => (el._2._2)).max

        model foreach (el => {
          // values can be greater than 1, therefore normalization is advised
          if ((el._2._2 - min) / (max - min) > threshold) predictions.update(el._1, el._2._1 :: predictions.getOrElse(el._1, Nil))
        })
        predictions.toMap
      }

      def confusionMatrix(predictions: Map[String, List[String]], song: String): (Int, Int, Int, Int) = {
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
       */
      private def precision(confusionMatrix: (Int, Int, Int, Int)): Double = {
        if (confusionMatrix._1 + confusionMatrix._2 > 0.0)
          confusionMatrix._1.toDouble / (confusionMatrix._1 + confusionMatrix._2)
        else
          0.0
      }

      /**
       * Calculate recall = TP / (TP + FN)
       */
      private def recall(confusionMatrix: (Int, Int, Int, Int)): Double = {
        if (confusionMatrix._1 + confusionMatrix._4 > 0.0)
          confusionMatrix._1.toDouble / (confusionMatrix._1 + confusionMatrix._4)
        else
          0.0
      }

      private def averagePrecision(model: Array[(String, (String, Double))]): List[(String, Double)] = {
        val thresholds = 0.0 :: 0.1 :: 0.2 :: 0.3 :: 0.4 :: 0.5 :: 0.6 :: 0.7 :: 0.8 :: 0.9 :: 1.0 :: Nil
        // predictions = (threshold) -> (user -> list of songs)
        val predictions = for {t <- thresholds} yield {
          predictionToClassLabels(model, t)
        }

        // TODO: evaluate recalls (and eventually precisions) beforehand to spare O(length of thresholds) calls to recall and confusionMatrix
        def singleAveragePrecision(song: String): Double = {
          thresholds.zipWithIndex.map(t => (
            if (t._2 == (thresholds.length - 1)) {
              0.0
            }
            else if (t._2 == (thresholds.length - 2)) {
              (recall(confusionMatrix(predictions(t._2), song)) - 0.0) * precision(confusionMatrix(predictions(t._2), song))
            } else
              (recall(confusionMatrix(predictions(t._2), song)) - recall(confusionMatrix(predictions(t._2 + 1), song))) * precision(confusionMatrix(predictions(t._2), song))
            )).sum
        }

        for {song <- newSongs} yield {
          (song, singleAveragePrecision(song))
        }
      }

      private def meanAveragePrecision(model: Array[(String, (String, Double))]): Double = {
        averagePrecision(model).map(ap => (
          ap._2
          )).sum / newSongs.length
      }

      def evaluateModel(model: Array[(String, (String, Double))]): Double = {
        // @model contains the prediction scores for test users
        meanAveragePrecision(model)
      }
    }

    val ubModel = MyUtils.time({
      // for each user, get user-based ranking
      val ubModel = testUsers.map(u => ubmFunctions.getModel(u))
      // save RDD on file
      ubModel.saveAsTextFile("DISTRIBUTED_OUTPUT/UBM")
      ubModel
    }, "(Distributed) user-based")
    val ubModel2 = MyUtils.time({
      val ubModel = songs.map(s => ubmFunctions.getModel2(s))
      ubModel.saveAsTextFile("DISTRIBUTED_OUTPUT/UBM2")
      ubModel
    }, "(Distributed) user-based 2")

    // TODO: probably we can iterate on users RDD instead of songs RDD (in this case we can delete the latter)
    val ibModel = MyUtils.time({
      // for each song, get item-based ranking
      val ibModel = songs.map(s => ibmFunctions.getModel(s))
      ibModel.saveAsTextFile("DISTRIBUTED_OUTPUT/IBM")
      ibModel
    }, "(Distributed) item-based")
    val ibModel2 = MyUtils.time({
      // for each song, get item-based ranking
      val ibModel = testUsers.map(u => ibmFunctions.getModel2(u))
      ibModel.saveAsTextFile("DISTRIBUTED_OUTPUT/IBM2")
      ibModel
    }, "(Distributed) item-based")

    val lcAlpha = 0.5
    val ubm = importModel("models/userBasedModel.txt")
    val ibm = importModel("models/itemBasedModel.txt")
    val lcModel = MyUtils.time({
      val modelsPair = ctx.parallelize(ubm.zip(ibm))
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      val lc = modelsPair.map {
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          (user1 -> (song1, rank1 * lcAlpha + rank2 * (1 - lcAlpha)))
      }
      lc.saveAsTextFile("DISTRIBUTED_OUTPUT/LCM")
      lc
    }, "(Distributed) linear combination")

    val itemBasedPercentage = 0.5
    val aModel = MyUtils.time({
      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      val modelsPair = ctx.parallelize(ubm.zip(ibm).zipWithIndex)
      val am = modelsPair.map({
        // for each pair
        case (couple, index) => couple match {
          case ((user1, song1, rank1), (user2, song2, rank2)) =>
            if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1))
        }
      })
      am.saveAsTextFile("DISTRIBUTED_OUTPUT/AM")
      am
    }, "(Distributed) aggregation model")

    val itemBasedProbability = 0.5
    val scModel = MyUtils.time({
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      val modelsPair = ctx.parallelize(ubm.zip(ibm))
      val scm = modelsPair.map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      })
      scm.saveAsTextFile("DISTRIBUTED_OUTPUT/SCM")
      scm
    }, "(Distributed) stochastic combination model")

    // writing on files 
    writeModelOnFile(ubModel.collect(), "models/userBasedModelD.txt")
    writeModelOnFile(ibModel.collect(), "models/itemBasedModelD.txt")
    writeModelOnFile(lcModel.collect(), "models/linearCombinationModelD.txt")
    writeModelOnFile(aModel.collect(), "models/aggregationModelD.txt")
    writeModelOnFile(scModel.collect(), "models/stochasticCombinationModelD.txt")

    // models evaluation
    evaluation_functions.evaluateModel(ubModel.flatMap(identity).collect())

  }
}
