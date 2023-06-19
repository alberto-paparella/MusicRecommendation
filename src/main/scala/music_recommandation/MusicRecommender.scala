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
   *
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
      case Array(u, s, _) =>
        // add user and song
        usersInFile add u
        mutSongs add s
        // update maps
        usersToSongsMap.update(u, s :: usersToSongsMap.getOrElse(u, Nil))
        mutSongsToUsersMap.update(s, u :: mutSongsToUsersMap.getOrElse(s, Nil))
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

  /**
   * Import data from the test labels file used for evaluation
   *
   * @param in file containing the test labels data
   * @return a map representing for each test user the latter half of its music history (i.e., the ground truth)
   */
  private def importTestLabels(in: BufferedSource): (Map[String, List[String]], List[String]) = {
    val testLabels = collection.mutable.Map[String, List[String]]()
    val newSongs = collection.mutable.Set[String]()
    // for each split line on "\t"
    for {
      line <- in.getLines().toList
    } yield line split "\t" match {
      case Array(u, s, _) =>
        // users are the same as in testUsers, while testLabels could contain new songs
        newSongs add s
        // update map
        testLabels.update(u, s :: testLabels.getOrElse(u, Nil))
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

  /**
   * (Sequential version) Given a rank function, it evaluates the ranks for combination (user, song)
   *
   * @param rank a function to evaluate the rank of each song for each user
   * @return the ranks for each song for each user according to the specified rank function
   */
  private def getModel(rank: (String, String) => Double): List[(String, (String, Double))] = {
    for {
      s <- songs
      u <- testUsers
      if !testUsersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
    } yield u -> (s, rank(u, s))
  }

  /**
   * (Parallel version) Given a rank function, it evaluates the ranks for combination (user, song)
   *
   * @param rank a function to evaluate the rank of each song for each user
   * @return the ranks for each song for each user according to the specified rank function
   */
  private def getModelP(rank: (String, String) => Double): ParSeq[(String, (String, Double))] = {
    for {
      s <- songs.iterator.toSeq.par
      u <- testUsers.iterator.toSeq.par
      if !testUsersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
    } yield u -> (s, rank(u, s))
  }

  /**
   * Get the user based model (i.e., using the cosine similarity between two users as rank function)
   *
   * @param parallel specify if the computation should be parallelized or not
   * @return the user based model
   */
  def getUserBasedModel(parallel: Boolean = false): GenSeq[(String, (String, Double))]  = {
    /**
     * Get the cosine similarity between two users
     *
     * @param user1 the first user (presumably from test users)
     * @param user2 the second user (presumably from trains users)
     * @return the cosine similarity between the two users
     */
    def cosineSimilarity(user1: String, user2: String): Double = {
      // number of songs listened by both users
      val numerator = songs.iterator.toSeq.map(song =>
        // if both users listened to song return 1, else 0
        if (testUsersToSongsMap(user1).contains(song) && trainUsersToSongsMap(user2).contains(song)) 1 else 0
      ).sum
      // _usersToSongMap(user).length represents how many songs the user listened to
      val denominator = sqrt(testUsersToSongsMap(user1).length) * sqrt(trainUsersToSongsMap(user2).length)
      if (denominator != 0) numerator / denominator else 0.0
    }

    /**
     * Calculate the rank of the song for the user (i.e., the sum of the cosine similarities with the users in train
     * users who listened to the song)
     *
     * @param user the user
     * @param song the song
     * @return the rank for each song for each user
     */
    def rank(user: String, song: String): Double = {
      for {
        u2 <- trainUsers
        if trainUsersToSongsMap(u2).contains(song)
      } yield {
        cosineSimilarity(user, u2)
      }
    } sum

    // calculate model
    if (parallel) getModelP(rank) else getModel(rank)
  }

  /**
   * Get the item based model (i.e., using the cosine similarity between two songs as rank function)
   *
   * @param parallel specify if the computation should be parallelized or not
   * @return the user item model
   */
  def getItemBasedModel(parallel: Boolean = false): GenSeq[(String, (String, Double))] = {
    /**
     * Get the cosine similarity between two songs
     *
     * @param song1 the first song
     * @param song2 the second song
     * @return the cosine similarity between the two songs
     */
    def cosineSimilarity(song1: String, song2: String): Double = {
      // number of users who listened to both songs
      val numerator = trainUsers.iterator.map(user =>
          // if the user listened to both songs return 1, else 0
          if (songsToUsersMap(song1).contains(user) && songsToUsersMap(song2).contains(user)) 1 else 0
      ).sum
      // songsToUsersMap(song).length represents hoe many users listened to the song
      val denominator = sqrt(songsToUsersMap(song1).length) * sqrt(songsToUsersMap(song2).length)
      if (denominator != 0) numerator / denominator else 0
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
      for {
        s2 <- songs
        if s2 != song
        if testUsersToSongsMap(user).contains(s2)
      } yield {
        cosineSimilarity(song, s2)
      }
    } sum

    // calculate model
    if (parallel) getModelP(rank) else getModel(rank)
  }

  /**
   * Calculate linear combination model given the user and the item based models and an alpha parameter
   *
   * @param ubm      the user based model
   * @param ibm      the item based model
   * @param alpha    weight of the contribute of the user based model; ibm will be weighted accordingly (lcAlpha - 1)
   * @param parallel specify if the computation should be parallelized or not
   * @return the linear combination model
   */
  def getLinearCombinationModel(ubm: List[(String, String, Double)],
                                ibm: List[(String, String, Double)],
                                alpha: Double,
                                parallel: Boolean = false): GenSeq[(String, (String, Double))] = {

    if (parallel) {
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).par.map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          // Catch error during zip
          if ((user1 != user2) || (song1 != song2)) System.exit(2)
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

  /**
   * Calculate aggregation model given the user and the item based models and an itemBasedPercentage parameter
   *
   * @param ubm                 the user based model
   * @param ibm                 the item based model
   * @param itemBasedPercentage how many ranks are taken from ubm and ibm
   * @param parallel            specify if the computation should be parallelized or not
   * @return the aggregation model
   */
  def getAggregationModel(ubm: List[(String, String, Double)],
                          ibm: List[(String, String, Double)],
                          itemBasedPercentage: Double = 0.5,
                          parallel: Boolean = false): GenSeq[(String, (String, Double))] = {

    // exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedPercentage < 0 || itemBasedPercentage > 1) {
      System.err.println("Percentage must be between 0 and 1\n")
      System.exit(-1)
    }

    if (parallel) {
      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      ubm.zip(ibm).zipWithIndex.par.map({
        // for each pair
        case (couple, index) => couple match {
          case ((user1, song1, rank1), (user2, song2, rank2)) =>
            // catch error during zip
            if ((user1 != user2) || (song1 != song2)) System.exit(2)
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1));
        }
      })
    } else {
      val length = ubm.length
      val itemBasedThreshold = (itemBasedPercentage * length).toInt
      // zip lists to get a list of couples (((user, song, rank_user), (user, song, rank_item)), index)
      ubm.zip(ibm).zipWithIndex.map({
        // for each pair
        case (couple, index) => couple match {
          case ((user1, song1, rank1), (user2, song2, rank2)) =>
            // catch error during zip
            if ((user1 != user2) || (song1 != song2)) System.exit(2)
            // based on the percentage, take the rank of one model
            if (index < itemBasedThreshold) (user1, (song1, rank2))
            else (user1, (song1, rank1))
        }
      })
    }

  }

  /**
   * Calculate stochastic combination model given the user and the item based models and an itemBasedProbability parameter
   *
   * @param ubm                  the user based model
   * @param ibm                  the item based model
   * @param itemBasedProbability threshold over which ranks are chosen from ibm model
   * @param parallel             specify if the computation should be parallelized or not
   * @return the stochastic combination model
   */
  def getStochasticCombinationModel(ubm: List[(String, String, Double)],
                                    ibm: List[(String, String, Double)],
                                    itemBasedProbability: Double = 0.5,
                                    parallel: Boolean = false): GenSeq[(String, (String, Double))] = {

    // Exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedProbability < 0 || itemBasedProbability > 1) {
      System.err.println("Probability must be between 0 and 1\n")
      System.exit(-1)
    }

    if (parallel) {
      val random = new Random
      // zip lists to get a list of couples ((user, song, rank_user), (user, song, rank_item))
      ubm.zip(ibm).par.map({
        // for each pair
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          // catch error during zip
          if ((user1 != user2) || (song1 != song2)) System.exit(2)
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
          // catch error during zip
          if ((user1 != user2) || (song1 != song2)) System.exit(2)
          // based on the probability, take the rank of one model
          if (random.nextFloat() < itemBasedProbability) (user1, (song1, rank2))
          else (user1, (song1, rank1))
      })
    }

  }

  /**
   * Store a model into a file; useful to separate the computations between user/item based models and combination models
   *
   * @param model          the model to be stored
   * @param outputFileName the path to the file the model is being stored to
   */
  def writeModelOnFile(model: GenSeq[(String, (String, Double))], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
    model foreach (el => {
      out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
    })
    out.close()
  }

  /**
   * Import a model previously stored in a file; useful to separate the computations between user/item based models
   * and the combination models
   *
   * @param pathToModel the path to the file containing the model
   * @return the model
   */
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
   *
   * @param model     the predictions scores for test users
   * @param threshold _ > threshold = 1, _ <= threshold = 0 (default 0.0)
   * @return map of list of songs the user will listen predicted by the model for each user
   */
  private def predictionToClassLabels(model: GenSeq[(String, (String, Double))], threshold: Double = 0.0): Map[String, List[String]] = {
    // model contains the prediction scores for test users
    val predictions = collection.mutable.Map[String, List[String]]()
    val min = model.map(el => el._2._2).min
    val max = model.map(el => el._2._2).max

    model foreach (el => {
      // values can be greater than 1, therefore normalization is advised
      if ((el._2._2 - min) / (max - min) > threshold) predictions.update(el._1, el._2._1 :: predictions.getOrElse(el._1, Nil))
    })
    predictions.toMap
  }

  /**
   * Calculate the confusion matrix wrt a specific class given the predictions provided by the model
   *
   * @param predictions the predictions provided by the model given a specific threshold
   * @param song        the class over we are calculating the confusion matrix
   * @return the confusion matrix for a specific class song
   */
  private def confusionMatrix(predictions: Map[String, List[String]], song: String): (Int, Int, Int, Int) = {
    // if threshold too high, user could not be in predictions!
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
  private def precision(confusionMatrix: (Int, Int, Int, Int)): Double = {
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
  private def recall(confusionMatrix: (Int, Int, Int, Int)): Double = {
    if (confusionMatrix._1 + confusionMatrix._4 > 0.0)
      confusionMatrix._1.toDouble / (confusionMatrix._1 + confusionMatrix._4)
    else
      0.0
  }

  /**
   * Calculate the average precision of the model for each class (i.e., each song)
   *
   * @param model the model to be evaluated
   * @param parallel specify if the computation should be parallelized or not
   * @return a list containing the average precisions for each class (i.e., each song)
   */
  private def averagePrecision(model: GenSeq[(String, (String, Double))], parallel: Boolean): GenSeq[(String, Double)] = {
    // the thresholds at which a rank is considered to be positive or negative (_ <= t == 0, _ > t == 1)
    val thresholds = 0.0 :: 0.1 :: 0.2 :: 0.3 :: 0.4 :: 0.5 :: 0.6 :: 0.7 :: 0.8 :: 0.9 :: Nil
    // predictions = (threshold) -> (user -> list of songs)
    val predictions = for {t <- thresholds} yield {predictionToClassLabels(model, t)}

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

    if (parallel)
      for {song <- newSongs.iterator.toSeq.par} yield {(song, singleAveragePrecision(song))}
    else
      for {song <- newSongs} yield {(song, singleAveragePrecision(song))}
  }

  /**
   * Calculate the mean Average Precision (mAP) of the given model
   *
   * @param model the model to be evaluated
   * @param parallel specify if the computation should be parallelized or not
   * @return the mAP of the model
   */
  private def meanAveragePrecision(model: GenSeq[(String, (String, Double))], parallel: Boolean): Double = {
    averagePrecision(model, parallel).map(ap => ap._2).sum / newSongs.length
  }

  /**
   * Evaluate the model using mAP
   *
   * @param model the model to be evaluated
   * @param parallel specify if the computation should be parallelized or not
   * @return the mAP of the model
   */
  def evaluateModel(model: GenSeq[(String, (String, Double))], parallel: Boolean = false): Double = {
    // model contains the prediction scores for test users
    meanAveragePrecision(model.toList, parallel)
  }

}