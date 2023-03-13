import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD

import scala.io.{BufferedSource, Source}
import scala.language.postfixOps
import scala.math.sqrt

val fileName: String = "../resources/train_triplets_10k.txt"
def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource(fileName).getPath)

// number of (maximum) users and songs to consider
val nUsedUsers = 500
val nUsedSongs = 500

//val conf = new SparkConf().setAppName("MusicRecommendation").setMaster("local[*]")
//val ctx = new SparkContext(conf)
val users = sc.textFile(fileName).map(line => line split "\t" slice(0, 1) mkString) distinct
val songs = sc.textFile(fileName).map(line => line split "\t" slice(1, 2) mkString) distinct

// given a user, it returns a list of all the songs (s)he listened to
def songsFilteredByUser(user: String): List[String] = (
  for {
    line <- in.getLines().toList.filter(line => line.contains(user))
  } yield line split "\t" match {
    case Array(_, song, _) => song
  }) distinct

val usersToSongsMap = users.map(user => user -> songsFilteredByUser(user)).collectAsMap()

def time[R](block: => R, operation: String = "unknown"): R = {
  // get start time
  val t0 = System.nanoTime()
  // execute code
  val result = block
  // get end time
  val t1 = System.nanoTime()
  // print elapsed time
  println(s"Elapsed time for $operation:\t" + (t1 - t0) / 1000000 + "ms")
  // return the result
  result
}

private def getModel(rank: (String, String) => Double) = {
  for {
    u <- users
    //s <- songs            // this had an error
  } yield {
    //u -> (s, rank(u,s))   // this had an error
    songs.map(s => u -> (s.mkString, rank(u, s.mkString)))
  }
}

def getUserBasedModel(): Unit = {
  // it calculates the cosine similarity between two users
  def cosineSimilarity(user1: String, user2: String): Double = {
    // Here, parallelization does not improve performances (TODO: check)
    val temp = songs.map(song => (
      // (numerator) if both users listened to song return 1, else 0
      if (usersToSongsMap(user1).contains(song) && usersToSongsMap(user2).contains(song)) 1 else 0,
      // (left sqrt arg) if user1 listened to song return 1, else 0
      if (usersToSongsMap(user1).contains(song)) 1 else 0,
      // (right sqrt arg) if user2 listened to song return 1, ese 0
      if (usersToSongsMap(user2).contains(song)) 1 else 0
    )).fold((0, 0, 0)) { (acc, tup) => (acc._1 + tup._1, acc._2 + tup._2, acc._3 + tup._3) }
    // pre-calculate denominator to catch if it is equal to 0
    val denominator = sqrt(temp._2) * sqrt(temp._3)
    if (denominator != 0) temp._1 / denominator else 0.0
  }

  def rank(user: String, song: String): Double = {
    users.map(u => if (u != user && usersToSongsMap(u).contains(song)) cosineSimilarity(user, u) else 0) sum

    /*for {
          u2 <- users
    //      if usersToSongsMap(u2).contains(song)
    //      if u2 == user
        } yield {
          cosineSimilarity(user, u2)
        }
      } sum*/
  }


  // Calculate model
  val userBasedModel = time(getModel(rank), "(Distributed) user-based model")
/*
  // Save model to file
  execution match {
    case 0 => writeModelOnFile(userBasedModel, "models/userBasedModel.txt")
    case 1 => writeModelOnFile(userBasedModel, "models/userBasedModelP.txt")
    case 2 =>
      // TODO
      System.exit(1)
      writeModelOnFile(userBasedModel, "models/userBasedModelD.txt")
    case _ =>
      System.exit(-1)

  userBasedModel*/
}



getUserBasedModel()



