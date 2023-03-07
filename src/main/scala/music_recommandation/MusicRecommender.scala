package music_recommandation

import java.io._
import scala.io._
import scala.language.postfixOps
import scala.math.sqrt
import scala.collection.parallel.CollectionConverters._

class MusicRecommender(private val users: IterableOnce[String], private val songs: IterableOnce[String], private val usersToSongsMap: Map[String, List[String]] ) {

  private def formula(rank: (String, String) => Double): IterableOnce[(String, (String, Double))] = {
    for {
      u <- users
      s <- songs
      //if !usersToSongsMap(u).contains(s) // Why?
    } yield {
      u -> (s, rank(u, s))
    }
  }

  private def time[R](block: => R, modelName: String): R = {
    // get start time
    val t0 = System.nanoTime()
    // execute code
    val result = block
    // get end time
    val t1 = System.nanoTime()
    // print elapsed time
    println(s"Elapsed time for $modelName:\t" + (t1 - t0)/1000000 + "ms")
    // return the result
    result
  }

  private def foldTuples(usersTuples:Iterator[(Int, Int, Int)]): (Int, Int, Int) = {
    usersTuples.fold((0, 0, 0)) {(acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3)}
  }

  def getItemBasedModelRank(outputFileName: String = "") = {
    // if the user listened to both songs return 1, else 0
    def numerator(song1: String, song2: String, user: String): Int =
      if (usersToSongsMap(user).contains(song1) && usersToSongsMap(user).contains(song2)) 1 else 0

    // it calculates the cosine similarity between two songs
    def cosineSimilarity(song1: String, song2: String): Double = {
      val usersTuples = users.iterator.map(user => (numerator(song1, song2, user),
        if (usersToSongsMap(user).contains(song1)) 1 else 0,
        if (usersToSongsMap(user).contains(song2)) 1 else 0
      ))

      val u = foldTuples(usersTuples)

      val denominator = sqrt(u._2) * sqrt(u._3)
      if (denominator != 0) u._1 / denominator else 0
    }

    def specificFormula(user: String, song: String): Double = {
      for {
        s2 <- songs //filter (s => s != song) //filter deprecated
        if s2 != song
        if usersToSongsMap(user).contains(s2)
      } yield { cosineSimilarity(song, s2) }
    } sum

    val itemBasedModel = time(formula(specificFormula), "item-based model")
    print(itemBasedModel)
    //if(outputFileName != "") time(writeModelOnFile(itemBasedModel, outputFileName), "writing")
  }

  def getUserBasedModelRank(outputFileName: String = "", parallel: Boolean = false): Unit = {
    // if both user listened to the same song return 1, else 0
    def numerator(user1:String, user2: String, song:String): Int =
      if (usersToSongsMap(user1).contains(song) && usersToSongsMap(user2).contains(song)) 1 else 0

    // it calculates the cosine similarity between two users
    def cosineSimilarity(user1: String, user2: String): Double = {
      val usersTuples = songs.iterator.map(song => (numerator(user1, user2, song),
        if (usersToSongsMap(user1).contains(song)) 1 else 0,
        if (usersToSongsMap(user2).contains(song)) 1 else 0,
      ))
      val u = foldTuples(usersTuples)
      val denominator = sqrt(u._2) * sqrt(u._3)
      if (denominator != 0) u._1 / denominator else 0
    }

    def specificFormula(user: String, song: String): Double = {
      for {
        u2 <- users //filter (u => u != user)   // filter deprecated
        if u2 != user
        if usersToSongsMap(u2).contains(song)
      } yield { cosineSimilarity(user, u2) }
    } sum

    val userBasedModel = if (parallel) {
      time(formula(specificFormula).iterator.toMap.par, "user-based model")
    } else {
      time(formula(specificFormula).iterator.toMap, "user-based model")
    }
    if(outputFileName != "") time(writeModelOnFile(userBasedModel.iterator.toMap, outputFileName), "writing")
  }

  private def importModel(pathToModel: String): List[(String, String, Double)] = {
    def modelFile: BufferedSource = Source.fromResource(pathToModel)
    val ordering = Ordering.Tuple3(Ordering.String, Ordering.String, Ordering.Double.IeeeOrdering.reverse)
    val model = modelFile.getLines().toList map (line => line split "\t" match {
      case Array(users, songs, ranks) => (users, songs, ranks.toDouble)
    }) sorted ordering
    model
  }

  def getLinearCombinationModelRank(alpha: Double, parallel: Boolean = false, outputFileName: String = ""): Unit = {
    val ubm = importModel("models/userBasedModel.txt")
    val ibm = importModel("models/itemBasedModel.txt")

    def linearCombination(): Iterator[(String, String, Double)] = {
      // zip lists to get a list of pairs ((user, song, rank_user), (user, song, rank_item))
      val userItemCouple = if (parallel) ubm.zip(ibm).par else ubm zip ibm
      // for each pair
      userItemCouple.iterator.map({
        case ((user1, song1, rank1), (user2, song2, rank2)) =>
          if(user1 != user2) println("Users different")
          if(song1 != song2) println("Songs different")
          // return (user, song, linear combination)
          (user1, song1, rank1 * alpha + rank2 * (1 - alpha))
      })
    }

    val linearCombined = time(linearCombination(), "linear combination model")
    //if(outputFileName != "") time(writeModelOnFile(linearCombined, outputFileName), "writing")
  }

   private def writeModelOnFile(model: Map[String, (String, Double)], outputFileName: String = ""): Unit = {
    val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
     model foreach (el => {
       out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
     })
    out.close()
  }
}