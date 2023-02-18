import java.io.{BufferedWriter, File, FileWriter}
import scala.language.postfixOps
import scala.io.{BufferedSource, Source}
import scala.math.sqrt

def in: BufferedSource = Source.fromFile(getClass.getClassLoader.getResource("train_triplets_2048.txt").getPath)

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

// if the user listened to both songs return 1, else 0
def numerator(song1: String, song2: String, user: String): Int =
  if (usersToSongsMap(user).contains(song1) && usersToSongsMap(user).contains(song2)) 1 else 0

// it calculates the cosine similarity between two songs
def cosineSimilarity(song1: String, song2: String): Double = {
  // for each user, check if (s)he has listened to both the songs
  val sameUsers = (usedUsers map (user => numerator(song1, song2, user))).sum
  // for each song, if user1 has listened to it sum 1 and at the end apply the square root
  val rootSong1 = sqrt((usedUsers map (user => if (usersToSongsMap(user).contains(song1)) 1 else 0)).sum)
  // for each song, if user2 has listened to it sum 1 and at the end apply the square root
  val rootSong2 = sqrt((usedUsers map (user => if (usersToSongsMap(user).contains(song2)) 1 else 0)).sum)
  // calculate cosine similarity
  sameUsers / (rootSong1*rootSong2)
}


def formula(weightFunction: (String, String) => Double): Seq[(String, String, Double)] = {

  def specificFormula(user: String, song: String): Double = {
    for { s2 <- usedSongs filter (s => s != song)}
      yield {
        val listened = if(usersToSongsMap(user).contains(s2)) 1 else 0
        listened*weightFunction(song, s2)
    }
  } sum

  def rank(): Seq[(String, String, Double)] = {
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

val itemBasedModel = formula(cosineSimilarity) sorted(Ordering.by[(String, String, Double), Double](_._3) reverse) groupBy(_._1)

val f = new File(getClass.getClassLoader.getResource("models/itemBasedModel.txt").getPath)
val bw = new BufferedWriter(new FileWriter(f))
itemBasedModel map(el => {
  el._2 map (row => {
    bw.write(row._1 + "\t" + row._2 + "\t" + row._3 + "\n")
  })
})
bw.close()