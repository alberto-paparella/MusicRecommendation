package music_recommandation

import java.io._
import scala.collection.GenSeq
import scala.collection.immutable._
import scala.collection.parallel.ParSeq
import scala.collection.parallel.mutable.ParArray
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
  private def extractData(in: BufferedSource): (Array[String], Map[String, Array[String]]) = {
    // all users in file
    val usersInFile = collection.mutable.Set[String]()
    // map user1->[{songs listened by user1}], ..., userN->[{songs listened by userN}]
    val mutUsersToSongsMap = collection.mutable.Map[String, List[String]]()
    // for each split line on "\t"
    for {
      line <- in.getLines().toArray
    } yield line split "\t" match {
      case Array(u, s, _) =>
        // add user and song
        usersInFile add u
        mutSongs add s
        // update maps
        mutUsersToSongsMap.update(u, s :: mutUsersToSongsMap.getOrElse(u, Nil))
        mutSongsToUsersMap.update(s, u :: mutSongsToUsersMap.getOrElse(s, Nil))
    }

    val usersToSongsMap: collection.mutable.Map[String, Array[String]] = mutUsersToSongsMap.map(entry => {
      entry match {
        case (k, v) => k -> v.toArray
      }
    })
    (usersInFile.toArray, usersToSongsMap.toMap)
  }

  // store all songs from both files
  private val mutSongs = collection.mutable.Set[String]()
  // map song1->[{users who listened to song1}], ..., songN->[{users who listened to songN}]
  private val mutSongsToUsersMap = collection.mutable.Map[String, List[String]]()
  // get train and test data from files
  private val (trainUsers, trainUsersToSongsMap) = extractData(trainFile)
  private val (testUsers, testUsersToSongsMap) = extractData(testFile)
  // convert mutable to immutable list
  private val songs: Array[String] = mutSongs.toArray
  // convert mutable to immutable map
  private val songsToUsersMap = mutSongsToUsersMap.map(entry => {
    entry match {
      case (k, v) => k -> v.toArray
    }
  }).toMap

  /**
   * Import data from the test labels file used for evaluation
   *
   * @param in file containing the test labels data
   * @return a map representing for each test user the latter half of its music history (i.e., the ground truth)
   */
  private def importTestLabels(in: BufferedSource): (Map[String, Array[String]], Array[String]) = {
    val mutTestLabels = collection.mutable.Map[String, List[String]]()
    val newSongs = collection.mutable.Set[String]()
    // for each split line on "\t"
    for {
      line <- in.getLines().toArray
    } yield line split "\t" match {
      case Array(u, s, _) =>
        // users are the same as in testUsers, while testLabels could contain new songs
        newSongs add s
        // update map
        mutTestLabels.update(u, s :: mutTestLabels.getOrElse(u, Nil))
    }

    val testLabels: Map[String, Array[String]] = mutTestLabels.map(entry => {
      entry match {
        case (key, value) => key -> value.toArray
      }
    }).toMap
    (testLabels, newSongs.toArray)
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
  private def getModel(rank: (String, String) => Double): Array[(String, (String, Double))] = {
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
  private def getModelP(rank: (String, String) => Double): ParArray[(String, (String, Double))] = {
    for {
      s <- songs.par
      u <- testUsers.par
      if !testUsersToSongsMap(u).contains(s) // considering only songs the user hasn't listened to yet
    } yield u -> (s, rank(u, s))
  }

  /**
   * Get the user based model (i.e., using the cosine similarity between two users as rank function)
   *
   * @param parallel specify if the computation should be parallelized or not
   * @return the user based model
   */
  def getUserBasedModel(): Array[(String, (String, Double))]  = {
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
    getModel(rank)
  }

  def getUserBasedModelP(): ParArray[(String, (String, Double))] = {
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
    getModelP(rank)
  }

  /**
   * Get the item based model (i.e., using the cosine similarity between two songs as rank function)
   *
   * @param parallel specify if the computation should be parallelized or not
   * @return the user item model
   */
  def getItemBasedModel(): Array[(String, (String, Double))] = {
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
    getModel(rank)
  }

  def getItemBasedModelP(): ParArray[(String, (String, Double))] = {
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
    getModelP(rank)
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
  def getLinearCombinationModel(ubm: Array[(String, String, Double)],
                                ibm: Array[(String, String, Double)],
                                alpha: Double): Array[(String, (String, Double))] = {

    // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
    ubm.zip(ibm).map({
      // for each pair
      case ((user1, song1, rank1), (user2, song2, rank2)) =>
        // Catch error during zip
        if ((user1 != user2) || (song1 != song2)) System.exit(2)
        // return (user, song, ranks linear combination)
        (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
    })
  }

  def getLinearCombinationModelP(ubm: Array[(String, String, Double)],
                                ibm: Array[(String, String, Double)],
                                alpha: Double): ParArray[(String, (String, Double))] = {
    // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
    ubm.zip(ibm).par.map({
      // for each pair
      case ((user1, song1, rank1), (user2, song2, rank2)) =>
        if ((user1 != user2) || (song1 != song2)) System.exit(2) // Catch error during zip
        // return (user, song, ranks linear combination)
        (user1, (song1, rank1 * alpha + rank2 * (1 - alpha)))
    })
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
  def getAggregationModel(ubm: Array[(String, String, Double)],
                          ibm: Array[(String, String, Double)],
                          itemBasedPercentage: Double = 0.5): Array[(String, (String, Double))] = {

    // exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedPercentage < 0 || itemBasedPercentage > 1) {
      System.err.println("Percentage must be between 0 and 1\n")
      System.exit(-1)
    }

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

  def getAggregationModelP(ubm: Array[(String, String, Double)],
                          ibm: Array[(String, String, Double)],
                          itemBasedPercentage: Double = 0.5): ParArray[(String, (String, Double))] = {

    // exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedPercentage < 0 || itemBasedPercentage > 1) {
      System.err.println("Percentage must be between 0 and 1\n")
      System.exit(-1)
    }
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
  def getStochasticCombinationModel(ubm: Array[(String, String, Double)],
                                    ibm: Array[(String, String, Double)],
                                    itemBasedProbability: Double = 0.5): Array[(String, (String, Double))] = {

    // Exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedProbability < 0 || itemBasedProbability > 1) {
      System.err.println("Probability must be between 0 and 1\n")
      System.exit(-1)
    }

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

  def getStochasticCombinationModelP(ubm: Array[(String, String, Double)],
                                    ibm: Array[(String, String, Double)],
                                    itemBasedProbability: Double = 0.5): ParArray[(String, (String, Double))] = {

    // Exit if percentage is not in the range 0 <= p <= 1
    if (itemBasedProbability < 0 || itemBasedProbability > 1) {
      System.err.println("Probability must be between 0 and 1\n")
      System.exit(-1)
    }
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
  }

  /**
   * Store a model into a file; useful to separate the computations between user/item based models and combination models
   *
   * @param model          the model to be stored
   * @param outputFileName the path to the file the model is being stored to
   */
  def writeModelOnFile(model: GenSeq[(String, (String, Double))], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(outputFileName)
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
  def importModelFromFile(pathToModel: String): Array[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromFile(pathToModel)
    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.reverse)
    val model = modelFile.getLines().toArray map (line => line split "\t" match {
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
    averagePrecision(model, parallel).map(ap => ap._2).foldLeft(0.0)(_+_) / newSongs.length
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