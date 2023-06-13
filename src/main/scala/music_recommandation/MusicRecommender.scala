package music_recommandation

import java.io._
import scala.collection.GenSeq
import scala.collection.immutable._
import scala.collection.parallel.ParSeq
import scala.io._
import scala.language.postfixOps
import scala.math.sqrt
import scala.util.Random

class MusicRecommender(trainFile: BufferedSource, testFile: BufferedSource, testLabelsFile: BufferedSource) {

  /**
   * ******************************************************************************************
   * CLASS INITIALIZATION
   * ******************************************************************************************
   */

  /**
   * Read file containing the data storing them in their relative structures
   * @param in file containing the data
   * @return list of users in the file and map of list of songs for each user
   */
  private def extractData(in: BufferedSource): (List[String], Map[String, List[String]]) = {
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
    (usersInFile.toList, usersToSongsMap.toMap)
  }

  // store all songs from both files
  private val mutSongs = collection.mutable.Set[String]()
  // map song1->[{users who listened to song1}], ..., songN->[{users who listened to songN}]
  private val mutSongsToUsersMap = collection.mutable.Map[String, List[String]]()
  // get train and test data from files
  private val (trainUsers, trainUsersToSongsMap) = extractData(trainFile)
  private val (testUsers, testUsersToSongsMap) = extractData(testFile)
  // convert mutable to immutable list
  private val songs: List[String] = mutSongs.toList
  // convert mutable to immutable map
  private val songsToUsersMap = mutSongsToUsersMap.toMap

  private def importTestLabels(in: BufferedSource): (Map[String, List[String]], List[String]) = {
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
  private val (testLabels, newSongs) = importTestLabels(testLabelsFile)

  /**
   * ******************************************************************************************
   * CLASS METHODS
   * ******************************************************************************************
   */

  private def getModel(rank: (String, String) => Double): List[(String, (String, Double))] = {
      for {
        u <- testUsers
        s <- songs
        //if !testUsersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
      } yield u -> (s, rank(u, s))
  }

  private def getModelP(rank: (String, String) => Double): ParSeq[(String, (String, Double))] = {
    // Main parallelization happens here
      for {
        u <- testUsers.iterator.toSeq.par
        s <- songs.iterator.toSeq.par
        //if !testUsersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
      } yield u -> (s, rank(u, s))
  }

  def getUserBasedModel(parallel: Boolean = false): GenSeq[(String, (String, Double))]  = {
    // it calculates the cosine similarity between two users
    def cosineSimilarity(user1: String, user2: String): Double = {
      val numerator = songs.iterator.toSeq.map(song =>
        // if both users listened to song return 1, else 0
        if (testUsersToSongsMap(user1).contains(song) && trainUsersToSongsMap(user2).contains(song)) 1 else 0
      ).sum
      // usersToSongMap(user).length represents how many songs the user listened to
      val denominator = sqrt(testUsersToSongsMap(user1).length) * sqrt(trainUsersToSongsMap(user2).length)
      if (denominator != 0) numerator / denominator else 0.0
    }

    def rank(user: String, song: String): Double = {
      for {
        u2 <- trainUsers
        if u2 != user && trainUsersToSongsMap(u2).contains(song)
      } yield {
        cosineSimilarity(user, u2)
      }
    } sum

    // Calculate model
    if (parallel)
      getModelP(rank)
    else
      getModel(rank)
  }

  def getItemBasedModel(parallel: Boolean = false): GenSeq[(String, (String, Double))] = {
    // it calculates the cosine similarity between two songs
    def cosineSimilarity(song1: String, song2: String): Double = {
      // Here, parallelization does not improve performances (TODO: check)
      val numerator = trainUsers.iterator.map(user =>
          // if the user listened to both songs return 1, else 0
          if (songsToUsersMap(song1).contains(user) && songsToUsersMap(song2).contains(user)) 1 else 0
      ).sum
      // pre-calculate denominator to catch if it is equal to 0
      val denominator = sqrt(songsToUsersMap(song1).length) * sqrt(songsToUsersMap(song2).length)
      if (denominator != 0) numerator / denominator else 0
    }

    def rank(user: String, song: String): Double = {
      // Here, parallelization does not improve performances (TODO: check)
      for {
        s2 <- songs
        if s2 != song
        if testUsersToSongsMap(user).contains(s2)
      } yield {
        cosineSimilarity(song, s2)
      }
    } sum

    // Calculate model
    if (parallel)
      getModelP(rank)
    else
      getModel(rank)
  }

  def getLinearCombinationModel(ubm: List[(String, String, Double)],
                                ibm: List[(String, String, Double)],
                                alpha: Double,
                                parallel: Boolean = false): GenSeq[(String, (String, Double))] = {

    if (parallel) {
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).par.map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
      })
    } else {
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // return (user, song, ranks linear combination)
          (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
      })
    }

  }

  def getAggregationModel(ubm: List[(String, String, Double)], ibm: List[(String, String, Double)],
                          itemBasedPercentage: Double = 0.5,
                          parallel: Boolean = false): GenSeq[(String, (String, Double))] = {

    // Exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedPercentage < 0 || itemBasedPercentage > 1) {
      System.err.println("Percentage must be between 0 and 1\n");
      System.exit(-1);
    }

    if (parallel) {
      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      // TODO: find a better solution than zipWithIndex (may be a slow solution)
      ubm.zip(ibm).zipWithIndex.par.map({
        // for each pair
        case (couple, index) => couple match {
          case ((user1, song1, rank1), (user2, song2, rank2)) =>
            if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1));
        }
      })
    } else {
      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      // TODO: find a better solution than zipWithIndex (may be a slow solution)
      ubm.zip(ibm).zipWithIndex.map({
        // for each pair
        case (couple, index) => couple match {
          case ((user1, song1, rank1), (user2, song2, rank2)) =>
            if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1))
        }
      })
    }

  }

  def getStochasticCombinationModel(ubm: List[(String, String, Double)],
                                    ibm: List[(String, String, Double)],
                                    itemBasedProbability: Double = 0.5,
                                    parallel: Boolean = false): GenSeq[(String, (String, Double))] = {

    // Exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedProbability < 0 || itemBasedProbability > 1) {
      System.err.println("Probability must be between 0 and 1\n");
      System.exit(-1);
    }

    if (parallel) {
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).par.map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      })
    } else {
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      })
    }

  }

  def writeModelOnFile(model: GenSeq[(String, (String, Double))], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    // we are printing to a file; therefore, parallelization would not improve performances
    model foreach (el => {
      out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
    })
    out.close()
  }

  def importModelFromFile(pathToModel: String): List[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)

    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.reverse)
    val model = modelFile.getLines().toList map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }

  /**
   * Convert the prediction scores to class labels
   * @param model the predictions scores for test users
   * @param threshold _ > threshold = 1, _ <= threshold = 0 (default 0.0)
   * @return map of list of songs the user will listen predicted by the model for each user
   */
  private def predictionToClassLabels(model: GenSeq[(String, (String, Double))], threshold: Double = 0.0): Map[String, List[String]] = {
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

  private def confusionMatrix(predictions: Map[String, List[String]], song: String): (Int, Int, Int, Int) = {
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

  private def averagePrecision(model: GenSeq[(String, (String, Double))]): List[(String, Double)] = {
    val thresholds = 0.0 :: 0.1 :: 0.2 :: 0.3 :: 0.4 :: 0.5 :: 0.6 :: 0.7 :: 0.8 :: 0.9 :: 1.0 :: Nil
    // predictions = (threshold) -> (user -> list of songs)
    val predictions = for {t <- thresholds} yield {predictionToClassLabels(model, t)}
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

    for {song <- newSongs} yield {(song, singleAveragePrecision(song))}
  }

  private def meanAveragePrecision(model: GenSeq[(String, (String, Double))]): Double = {
    averagePrecision(model).map(ap => (
      ap._2
    )).sum / newSongs.length
  }

  def evaluateModel(model: GenSeq[(String, (String, Double))]): Double = {
    // @model contains the prediction scores for test users
    meanAveragePrecision(model.toList)
  }

}