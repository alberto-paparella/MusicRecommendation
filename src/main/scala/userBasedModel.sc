import scala.language.postfixOps
import scala.io.{BufferedSource, Source}
import scala.math.sqrt
import java.io._
import scala.collection.parallel.CollectionConverters._

// Sequential
def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("train_triplets_50k.txt").getPath)

// load all users
val allUsers = in.getLines().toList map (line => line split "\t" slice(0,1) mkString) distinct
// load all songs
val allSongs = in.getLines().toList map (line => line split "\t" slice(1,2) mkString) distinct

// TEST-ONLY: this is a subset of all users
val users = allUsers slice(0,200)
// TEST-ONLY: this is a subset of all songs
val songs = allSongs slice(0,200)

// given a user, it returns a list of all the songs (s)he listened to
def songsFilteredByUser(user:String): List[String] = (for {
  line <- in.getLines().toList.filter(line => line.contains(user))
} yield line split "\t" match {
  case Array(_, song, _) => song
}) distinct

// create a map user1->[song1, song2, ...], user2->[song3,...]
val usersToSongsMap = users map (user => (user, songsFilteredByUser(user))) toMap

def formula(rank: (String, String) => Double): List[(String, (String, Double))] = {
  for {
    u <- users
    s <- songs
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

private def writeModelOnFile(model: Map[String, (String, Double)], outputFileName: String = ""): Unit = {
  val out = new PrintWriter(getClass.getClassLoader.getResource(outputFileName).getPath)
  model foreach (el => {
    out.write(s"${el._1}\t${el._2._1}\t${el._2._2}\n")
  })
  out.close()
}

val userBasedModel = time(formula(specificFormula).iterator.toMap.par, "user-based model")
time(writeModelOnFile(userBasedModel.iterator.toMap, "models/userBasedModel.txt"), "writing")