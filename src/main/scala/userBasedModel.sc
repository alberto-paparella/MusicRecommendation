import java.io.{BufferedWriter, File, FileOutputStream, OutputStreamWriter}
import scala.language.postfixOps
import scala.io.{BufferedSource, Source}
import scala.math.sqrt

def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("train_triplets_10k.txt").getPath)

// load all songs
val songs = in.getLines().toList map (line => line split "\t" slice(1,2) mkString) distinct
// load all users
val users = in.getLines().toList map (line => line split "\t" slice(0,1) mkString) distinct

// TEST-ONLY: this is a subset of all users
val usedUsers = users slice(0,100)
// TEST-ONLY: this is a subset of all songs
val usedSongs = songs slice(0,100)

// given a user, it returns a list of all the songs (s)he listened to
def songsFilteredByUser(user:String) :List[String] = (for {
  line <- in.getLines().toList.filter(line => line.contains(user))
} yield line split "\t" match {
  case Array(_, song, _) => song
}) distinct

// create a map user1->[song1, song2, ...], user2->[song3,...]
val usersToSongsMap = users map (user => (user, songsFilteredByUser(user))) toMap

// if both user listened to the same song return 1, else 0
def numerator(user1:String, user2: String, song:String): Int =
  if (usersToSongsMap(user1).contains(song) && usersToSongsMap(user2).contains(song)) 1 else 0

// it calculates the cosine similarity between two users
def cosineSimilarity(user1: String, user2: String): Double = {
  // for each song, check if both have listened to it
  val sameSongs = (usedSongs map (song => numerator(user1, user2, song))).sum
  // for each song, if user1 has listened to it sum 1 and at the end apply the square root
  val rootUser1 = sqrt((usedSongs map (song => if (usersToSongsMap(user1).contains(song)) 1 else 0)).sum)
  // for each song, if user2 has listened to it sum 1 and at the end apply the square root
  val rootUser2 = sqrt((usedSongs map (song => if (usersToSongsMap(user2).contains(song)) 1 else 0)).sum)
  // calculate cosine similarity
  if (rootUser1*rootUser2 == 0) 0
  else sameSongs / (rootUser1*rootUser2)
}

def formula(weightFunction: (String, String) => Double): Seq[(String, String, Double)] = {

  def specificFormula(user: String, song: String): Double = {
    for { u2 <- usedUsers filter (u => u != user)}
      yield {
        val listened = if(usersToSongsMap(u2).contains(song)) 1 else 0
        listened*weightFunction(user, u2)
      }
  } sum

  def rank(): List[(String, String, Double)] = {
    for {
      u <- usedUsers
      s <- usedSongs filter(song => !usersToSongsMap(u).contains(song))
    } yield {
      def rank: Double = specificFormula(u,s)
      (u, s, rank)
    }
  }

  rank()
}

def time[R](block: => R): R = {
  // get start time
  val t0 = System.nanoTime()
  // execute code
  val result = block
  // get end time
  val t1 = System.nanoTime()
  // print elapsed time
  println("Elapsed time: " + (t1 - t0)/1000000 + "ms")
  // return the result
  result
}

val userBasedModel = time(formula(cosineSimilarity)) sorted(Ordering.by[(String, String, Double), Double](_._3) reverse) groupBy(_._1)

val f = new File(getClass.getClassLoader.getResource("models/userBasedModel.txt").getPath)
val bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)))
userBasedModel map(el => {
  el._2 map (row => {
    bw.write(row._1 + "\t" + row._2 + "\t" + row._3 + "\n")
  })
})
bw.close()