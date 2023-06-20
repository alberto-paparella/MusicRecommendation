# Music Recommendation
Repository for the project of the Scalable and Cloud Computing course @unibo, a.y. 2022-23.

Inspired by the Million Song Dataset challenge on Kaggle, a comparison of various solutions to the problem
exploiting the MapReduce paradigm and providing an environment for sequential, parallel and distributed
execution. Hence, we aim to provide not only a comparison between the different algorithms, but also an
analysis of the behaviours of these algorithms across the different executions.

The different algorithms produced can be found in the `src/main/scala/` directory as scala worksheet
files. To execute the algorithms, one should download the <b>Echo Nest Taste Profile Subset</b> dataset from
http://millionsongdataset.com/tasteprofile/ and place the `train_triplets.txt` file under the `src/main/resources/`
directory. Once executed, the results of the training will be available through a text file with the same name of the
algorithm which can be found under `target/scala-2.13/classes/models/`. Make sure to also have an empty file in
`src/main/resources/models/` with the same name of the model (in case not, create it, as it will be used to dynamically
create the path for the output model, which will still be found under the `target/scala-2.13/classes/models/` path).

We plan to implement the following algorithms:
- UserBasedModel
- ItemBasedModel
- LinearCombinationModel
- AggregationModel
- StochasticAggregationModel

## How to execute code on Google Cloud Platform
1. Be sure that:
    * Every file path in code is in the local path (e.g., "train_100_50.txt" instead of "~/MusicReccomender/.../train_100_50.txt")
    * All files are loaded with `Source.fromResource` instead of `Source.fromFile`
    * You have already created a Google Cloud Project ([here's a guide](https://cloud.google.com/dataproc/docs/guides/setup-project))
    * You have already installed Google Cloud CLI ([here's a guide](https://cloud.google.com/sdk/docs/install))
2. Create an assembly *.jar* package using the following command (in the project directory):
   ```sh
   sbt clean assembly
   ```
   * (Optional, but strongly recommended) You can check if the code works: go to `MusicReccomender/target/scala-2.12/` and run
     ```shell
       java -jar package.jar
       ```
3. Create a new bucket inside the project in Google Cloud Platform (in this example it is called `mr`)
4. Copy the package created inside `MusicReccomender/target/scala-2.12/` to the bucket with the following command:
   ```shell
   gsutil cp package.jar gs://mr
   ```
5. Create a Compute Engine Cluster on Google Cloud Platform
    * Be careful to choose the correct linux image, which is *2.1 (Debian 11, Hadoop 3.3, Spark 3.3) first release: 22/01/2021*
    * You can plan the elimination of the cluster so that you won't waste credits 
6. Create and send a new job by:
   1. Selecting the created cluster
   2. Choosing *Spark* job  as "Type of job"
   3. Typing `gs://mr/package.jar` in "Main class or jar" 

## Some notes about some choices made during the project

### getModel alternatives:

- using only map:

```
songs.map(s => if (!testUsersToSongsMap(user).contains(s)) user -> (s, rank(user, s)) else user -> (s, 0.0))
```

- using external map and internal for:

```
songs map (s => {
  for {
    u <- testUsers filter (u => !testUsersToSongsMap(u).contains(s))
  } yield u -> (s, rank(u, s))
})
```

- using only for:

```
for {
  s <- songs
  u <- testUsers
  if !testUsersToSongsMap(u).contains(s)
} yield u -> (s, rank(u, s))
```

The latter has been (sperimentally) proven to be the most efficient for both sequential and parallel computations, while in the distributed version the second works better, as we can distribute only on one RDD (more considerations on this later).

- Parallel version of the third alternative:

```
for {
  s <- songs.iterator.toSeq.par
  u <- testUsers.iterator.toSeq.par
  if !testUsersToSongsMap(u).contains(s)
} yield u -> (s, rank(u, s))
```

In the for construct over both structures, the if statement has been proven to work better than the filter, differently from the other cases (i.e., for over one single structure and map).

- Distributed version of the second alternative:

```
object _BasedModel {

  ...

  def getRanks1(user: String):ParSeq[(String, (String, Double))] = {
    // foreach song, calculate the score for the user
    for {
      song <- songs.iterator.toSeq.par filter (song => !testUsersToSongsMap(user).contains(song))
    } yield {
      user -> (song, rank(user, song))
    }
  }

  def getRanks2(song: String): ParSeq[(String, (String, Double))] = {
    // foreach user, calculate the score for the user
    for {
      user <- testUsers.iterator.toSeq.par filter (user => !testUsersToSongsMap(user).contains(song))
    } yield {
      user -> (song, rank(user, song))
    }
  }
}

...

ctx.parallelize(testUsers).map(user => _BasedModel.getRanks1(user).seq).collect.flatten
ctx.parallelize(songs).map(song => _BasedModel.getRanks2(song).seq).collect.flatten

```

About distribution, we also have to take in account the number of nodes and cores per node that we can use. For
instance, in our case dividing the computation over the songs has proven to be better than dividing over the number of
users in all the experiments, therefore if we had more nodes than cores per node we would distribute over the songs
mapping them over the getRanks2 function; otherwise, if we had more cores per node rather than nodes, we would
distribute over the users mapping them over the getRanks1 function and making use of the greater parallelization
happening inside every single node. The same valuations stand in case for some reason the number of users grows
exponentially while the number of songs stays the same.

## Experiments

The following results are obtained on an Intel® Core™ i5-8250U Processor, 6M Cache, up to 3.40 GHz, 4 cores, 8 threads.
All times are in ms.

Specifically, we only reported times for our computation of interest: user based model (ubm), item based model (ibm) and
the evaluation (mAP), calculated as a mean of the execution times of the evaluation function for each model. Combination
models are not considered, as the execution time of the combination between ubm and ibm has never exceeded one second
and therefore parallelization and distribution would not be useful (keep in mind that to compute the combination models
one should have computed both ubm and ibm beforehand; therefore, all combinations are in fact taking advantage of
distribution, and each computation time for a combination model can be simplified as time_ubm + time_ibm + time_comb,
with time_comb << time_ubm + time_ibm such that time_comb is negligible). 

We designed the sets of experiments: the first two are meant to compare the scalability of the proposed solution and the
efficiency of the models and the evaluation function, while the third one is meant to compare the models to find which
one is the best, following the challenge spirit in which the problem has first been made (Kaggle). 

The first set of experiments is specifically meant to show the scalability of the mAP distribution, as it depends on the
number of NewSongs (which depends on the number of test users), making it difficult to emphasize it in the other
experiments, as we had to stick with 10 test users for computation issues.

The second set of experiments is meant to show the scalability of the application over an increasing number of train
users, which increases the number of considered songs, which are the main character of our distribution solution.

Finally, the third experiment is meant to compare the various models using as evaluation function the mean average
precision to find the best one. This is done only on the distributed version, making use of the proven scalability to
compute the models on a much bigger dataset.

### Incrementing the number of test users

- Sequential:

| TrainUsers | TestUsers | Songs | NewSongs | ubm    | ibm    | mAP (mean) |
|------------|-----------|-------|----------|--------|--------|------------|
| 100        | 10        | 4798  | 262      | 58658  | 48383  | 356        |
| 100        | 50        | 5679  | 1394     | 443302 | 370886 | 7058       |
| 100        | 100       | 6620  | 2480     | 991868 | 887161 | 27347      |

- Parallel:

| TrainUsers | TestUsers | Songs | NewSongs | ubm    | ibm    | mAP (mean) |
|------------|-----------|-------|----------|--------|--------|------------|
| 100        | 10        | 4798  | 262      | 24893  | 15906  | 117        |
| 100        | 50        | 5679  | 1394     | 165072 | 133247 | 2249       |
| 100        | 100       | 6620  | 2480     | 391485 | 377474 | 8003       |

- Distributed (note: take this with a grain of salt, as we are running Spark locally, therefore virtualizing the
distribution)

| TrainUsers | TestUsers | Songs | NewSongs | ubm    | ibm    | mAP (mean) |
|------------|-----------|-------|----------|--------|--------|------------|
| 100        | 10        | 4798  | 262      | 20303  | 8082   | 393        |
| 100        | 50        | 5679  | 1394     | 163840 | 60680  | 3440       |
| 100        | 100       | 6620  | 2480     | 379735 | 176130 | 10123      |

![models100](images/models100.png)
![mAP100](images/mAP100.png)

### Incrementing the number of train users

- Sequential:

| TrainUsers | TestUsers | Songs | NewSongs | ubm    | ibm    |
|------------|-----------|-------|----------|--------|--------|
| 100        | 10        | 4798  | 262      | 58658  | 48383  |
| 200        | 10        | 8763  | 180      | 204155 | 128436 |
| 300        | 10        | 11660 | 299      | 499692 | 313148 |

- Parallel:

| TrainUsers | TestUsers | Songs | NewSongs | ubm    | ibm    | 
|------------|-----------|-------|----------|--------|--------|
| 100        | 10        | 4798  | 262      | 24893  | 15906  |
| 200        | 10        | 8763  | 180      | 76490  | 43693  |
| 300        | 10        | 11660 | 299      | 183963 | 120420 |

- Distributed (note: take this with a grain of salt, as we are running Spark locally, therefore virtualizing the
  distribution)

| TrainUsers | TestUsers | Songs | NewSongs | ubm    | ibm    |
|------------|-----------|-------|----------|--------|--------|
| 100        | 10        | 4798  | 262      | 20303  | 8082   |
| 200        | 10        | 8763  | 180      | 65032  | 29017  |
| 300        | 10        | 11660 | 299      | 195862 | 65393  |

![models10](images/models10.png)

<details> 
<summary>Results with 100 train users and 10 test users</summary>

- Songs: 4798
- New songs: 262
- Elapsed time for (Sequential) user-based model:	58658ms (58658986898ns)
- Elapsed time for (Parallel) user-based model:	24893ms (24893316523ns)
- Elapsed time for (Distributed) user-based:	20303ms (20303431284ns)
- Elapsed time for (Sequential) item-based model:	48383ms (48383384237ns)
- Elapsed time for (Parallel) item-based model:	15906ms (15906778693ns)
- Elapsed time for (Distributed) item-based:	8082ms (8082279295ns)
- Elapsed time for (Sequential) linear-combination model:	33ms (33520200ns)
- Elapsed time for (Parallel) linear-combination model:	27ms (27268203ns)
- Elapsed time for (Distributed) linear combination:	290ms (290478902ns)
- Elapsed time for (Sequential) aggregation model:	39ms (39876716ns)
- Elapsed time for (Parallel) aggregation model:	29ms (29731934ns)
- Elapsed time for (Distributed) aggregation model:	339ms (339264968ns)
- Elapsed time for (Sequential) stochastic-combination model:	35ms (35551846ns)
- Elapsed time for (Parallel) stochastic-combination model:	24ms (24309424ns)
- Elapsed time for (Distributed) stochastic combination model:	296ms (296024203ns)
- Elapsed time for (Sequential) user-based model mAP:	383ms (383206254ns)
- Elapsed time for (Parallel) user-based model mAP:	115ms (115394876ns)
- Elapsed time for (Distributed) user-based model mAP:	448ms (448760836ns)
- Elapsed time for (Sequential) item-based model mAP:	462ms (462000005ns)
- Elapsed time for (Parallel) item-based model mAP:	137ms (137445127ns)
- Elapsed time for (Distributed) item-based model mAP:	452ms (452198796ns)
- Elapsed time for (Sequential) linear-combination model mAP:	366ms (366446918ns)
- Elapsed time for (Parallel) linear-combination model mAP:	127ms (127101429ns)
- Elapsed time for (Distributed) linear-combination model mAP:	375ms (375027340ns)
- Elapsed time for (Sequential) aggregation model mAP:	292ms (292943075ns)
- Elapsed time for (Parallel) aggregation model mAP:	109ms (109518704ns)
- Elapsed time for (Distributed) aggregation model mAP:	360ms (360157134ns)
- Elapsed time for (Sequential) stochastic-combination model mAP:	273ms (273454369ns)
- Elapsed time for (Parallel) stochastic-combination model mAP:	99ms (99347299ns)
- Elapsed time for (Distributed) stochastic-combination model mAP:	332ms (332107192ns)
- (Sequential) user-based model mAP: 0.06180479825517996
- (Parallel) user-based model mAP: 0.06180479825517996
- (Distributed) user-based model mAP: 0.06180479825517996
- (Sequential) item-based model mAP: 0.09904580152671755
- (Parallel) item-based model mAP: 0.09904580152671755
- (Distributed) item-based model mAP: 0.09904580152671755
- (Sequential) linear-combination model mAP: 0.1025445292620865
- (Parallel) linear-combination model mAP: 0.1025445292620865
- (Distributed) linear-combination model mAP: 0.1025445292620865
- (Sequential) aggregation model model mAP: 0.06371319520174483
- (Parallel) aggregation model mAP: 0.06371319520174483
- (Distributed) aggregation model model mAP: 0.06371319520174483
- (Sequential) stochastic-combination model mAP: 0.06667575427117413
- (Parallel) stochastic-combination model mAP: 0.08829516539440202
- (Distributed) stochastic-combination model mAP: 0.08416939294801888

</details>

<details> 
<summary>Results with 100 train users and 50 test users</summary>

- Songs: 5679
- New songs: 1394
- Elapsed time for (Sequential) user-based model:	443302ms (443302662242ns)
- Elapsed time for (Parallel) user-based model:	165072ms (165072471133ns)
- Elapsed time for (Distributed) user-based:	163840ms (163840179619ns)
- Elapsed time for (Sequential) item-based model:	370886ms (370886104809ns)
- Elapsed time for (Parallel) item-based model:	133247ms (133247526462ns)
- Elapsed time for (Distributed) item-based:	60680ms (60680446360ns)
- Elapsed time for (Sequential) linear-combination model:	184ms (184318199ns)
- Elapsed time for (Parallel) linear-combination model:	87ms (87586201ns)
- Elapsed time for (Distributed) linear combination:	1661ms (1661453565ns)
- Elapsed time for (Sequential) aggregation model:	94ms (94727328ns)
- Elapsed time for (Parallel) aggregation model:	206ms (206422236ns)
- Elapsed time for (Distributed) aggregation model:	2077ms (2077728369ns)
- Elapsed time for (Sequential) stochastic-combination model:	74ms (74202839ns)
- Elapsed time for (Parallel) stochastic-combination model:	173ms (173552138ns)
- Elapsed time for (Distributed) stochastic combination model:	1535ms (1535567097ns)
- Elapsed time for (Sequential) user-based model mAP:	4250ms (4250434755ns)
- Elapsed time for (Parallel) user-based model mAP:	2087ms (2087645203ns)
- Elapsed time for (Distributed) user-based model mAP:	3315ms (3315580424ns)
- Elapsed time for (Sequential) item-based model mAP:	7922ms (7922687700ns)
- Elapsed time for (Parallel) item-based model mAP:	1601ms (1601470037ns)
- Elapsed time for (Distributed) item-based model mAP:	3360ms (3360055376ns)
- Elapsed time for (Sequential) linear-combination model mAP:	9066ms (9066474651ns)
- Elapsed time for (Parallel) linear-combination model mAP:	2386ms (2386394758ns)
- Elapsed time for (Distributed) linear-combination model mAP:	3447ms (3447589278ns)
- Elapsed time for (Sequential) aggregation model mAP:	9999ms (9999879852ns)
- Elapsed time for (Parallel) aggregation model mAP:	2936ms (2936221676ns)
- Elapsed time for (Distributed) aggregation model mAP:	4055ms (4055350043ns)
- Elapsed time for (Sequential) stochastic-combination model mAP:	7737ms (7737182829ns)
- Elapsed time for (Parallel) stochastic-combination model mAP:	2234ms (2234128152ns)
- Elapsed time for (Distributed) stochastic-combination model mAP:	3021ms (3021076945ns)
- (Sequential) user-based model mAP: 0.03201406830127337
- (Parallel) user-based model mAP: 0.03201406830127337
- (Distributed) user-based model mAP: 0.03201406830127337
- (Sequential) item-based model mAP: 0.07304353273841559
- (Parallel) item-based model mAP: 0.07304353273841559
- (Distributed) item-based model mAP: 0.07304353273841559
- (Sequential) linear-combination model mAP: 0.07322915733221809
- (Parallel) linear-combination model mAP: 0.07322915733221809
- (Distributed) linear-combination model mAP: 0.07322915733221809
- (Sequential) aggregation model model mAP: 0.04614277953880507
- (Parallel) aggregation model mAP: 0.04614277953880507
- (Distributed) aggregation model model mAP: 0.04614277953880507
- (Sequential) stochastic-combination model mAP: 0.05351509988897473
- (Parallel) stochastic-combination model mAP: 0.051781733009349226
- (Distributed) stochastic-combination model mAP: 0.05423599782160435

</details>

<details> 
<summary>Results with 100 train users and 100 test users</summary>

- Songs: 6620
- New songs: 2480
- Elapsed time for (Sequential) user-based model:	991868ms (991868636344ns)
- Elapsed time for (Parallel) user-based model:	391485ms (391485313932ns)
- Elapsed time for (Distributed) user-based:	379735ms (379735402566ns)
- Elapsed time for (Sequential) item-based model:	887161ms (887161514646ns)
- Elapsed time for (Parallel) item-based model:	377474ms (377474236042ns)
- Elapsed time for (Distributed) item-based:	176130ms (176130770260ns)
- Elapsed time for (Sequential) linear-combination model:	374ms (374935773ns)
- Elapsed time for (Parallel) linear-combination model:	164ms (164122590ns)
- Elapsed time for (Distributed) linear combination:	3681ms (3681257329ns)
- Elapsed time for (Sequential) aggregation model:	539ms (539084763ns)
- Elapsed time for (Parallel) aggregation model:	234ms (234358408ns)
- Elapsed time for (Distributed) aggregation model:	4429ms (4429359137ns)
- Elapsed time for (Sequential) stochastic-combination model:	178ms (178715968ns)
- Elapsed time for (Parallel) stochastic-combination model:	395ms (395444889ns)
- Elapsed time for (Distributed) stochastic combination model:	3552ms (3552846833ns)
- Elapsed time for (Sequential) user-based model mAP:	19188ms (19188731520ns)
- Elapsed time for (Parallel) user-based model mAP:	6803ms (6803050050ns)
- Elapsed time for (Distributed) user-based model mAP:	9733ms (9733187920ns)
- Elapsed time for (Sequential) item-based model mAP:	23647ms (23647600538ns)
- Elapsed time for (Parallel) item-based model mAP:	8574ms (8574184133ns)
- Elapsed time for (Distributed) item-based model mAP:	10300ms (10300000722ns)
- Elapsed time for (Sequential) linear-combination model mAP:	30798ms (30798891094ns)
- Elapsed time for (Parallel) linear-combination model mAP:	7884ms (7884662348ns)
- Elapsed time for (Distributed) linear-combination model mAP:	11020ms (11020243833ns)
- Elapsed time for (Sequential) aggregation model mAP:	33962ms (33962035397ns)
- Elapsed time for (Parallel) aggregation model mAP:	9335ms (9335162218ns)
- Elapsed time for (Distributed) aggregation model mAP:	12144ms (12144616411ns)
- Elapsed time for (Sequential) stochastic-combination model mAP:	29140ms (29140917711ns)
- Elapsed time for (Parallel) stochastic-combination model mAP:	7420ms (7420243536ns)
- Elapsed time for (Distributed) stochastic-combination model mAP:	9941ms (9941298168ns)
- (Sequential) user-based model mAP: 0.016535498602550824
- (Parallel) user-based model mAP: 0.016535498602550824
- (Distributed) user-based model mAP: 0.016535498602550824
- (Sequential) item-based model mAP: 0.03945161744725745
- (Parallel) item-based model mAP: 0.03945161744725745
- (Distributed) item-based model mAP: 0.03945161744725745
- (Sequential) linear-combination model mAP: 0.04045516217984957
- (Parallel) linear-combination model mAP: 0.04045516217984957
- (Distributed) linear-combination model mAP: 0.04045516217984957
- (Sequential) aggregation model model mAP: 0.02603719801265665
- (Parallel) aggregation model mAP: 0.02603719801265665
- (Distributed) aggregation model model mAP: 0.02603719801265665
- (Sequential) stochastic-combination model mAP: 0.029517703115932412
- (Parallel) stochastic-combination model mAP: 0.025638645633561417
- (Distributed) stochastic-combination model mAP: 0.025101654661291623

</details>

<details> 
<summary>Results with 200 train users and 10 test users</summary>

- Songs: 8763
- New songs: 180
- Elapsed time for (Sequential) user-based model:	204155ms (204155158480ns)
- Elapsed time for (Parallel) user-based model:	76490ms (76490797927ns)
- Elapsed time for (Distributed) user-based:	65032ms (65032787207ns)
- Elapsed time for (Sequential) item-based model:	128436ms (128436708772ns)
- Elapsed time for (Parallel) item-based model:	43693ms (43693541482ns)
- Elapsed time for (Distributed) item-based:	29017ms (29017904363ns)
- Elapsed time for (Sequential) linear-combination model:	50ms (50372045ns)
- Elapsed time for (Parallel) linear-combination model:	46ms (46787857ns)
- Elapsed time for (Distributed) linear combination:	451ms (451519024ns)
- Elapsed time for (Sequential) aggregation model:	57ms (57997973ns)
- Elapsed time for (Parallel) aggregation model:	35ms (35870119ns)
- Elapsed time for (Distributed) aggregation model:	750ms (750920276ns)
- Elapsed time for (Sequential) stochastic-combination model:	32ms (32620384ns)
- Elapsed time for (Parallel) stochastic-combination model:	39ms (39470361ns)
- Elapsed time for (Distributed) stochastic combination model:	501ms (501646439ns)
- Elapsed time for (Sequential) user-based model mAP:	437ms (437976365ns)
- Elapsed time for (Parallel) user-based model mAP:	158ms (158159590ns)
- Elapsed time for (Distributed) user-based model mAP:	503ms (503051928ns)
- Elapsed time for (Sequential) item-based model mAP:	728ms (728031925ns)
- Elapsed time for (Parallel) item-based model mAP:	228ms (228635286ns)
- Elapsed time for (Distributed) item-based model mAP:	794ms (794649605ns)
- Elapsed time for (Sequential) linear-combination model mAP:	593ms (593021898ns)
- Elapsed time for (Parallel) linear-combination model mAP:	202ms (202417869ns)
- Elapsed time for (Distributed) linear-combination model mAP:	554ms (554476586ns)
- Elapsed time for (Sequential) aggregation model mAP:	427ms (427440330ns)
- Elapsed time for (Parallel) aggregation model mAP:	156ms (156199200ns)
- Elapsed time for (Distributed) aggregation model mAP:	486ms (486076712ns)
- Elapsed time for (Sequential) stochastic-combination model mAP:	436ms (436432375ns)
- Elapsed time for (Parallel) stochastic-combination model mAP:	159ms (159345109ns)
- Elapsed time for (Distributed) stochastic-combination model mAP:	478ms (478264999ns)
- (Sequential) user-based model mAP: 0.06740740740740742
- (Parallel) user-based model mAP: 0.06740740740740742
- (Distributed) user-based model mAP: 0.06740740740740742
- (Sequential) item-based model mAP: 0.11175925925925927
- (Parallel) item-based model mAP: 0.11175925925925927
- (Distributed) item-based model mAP: 0.11175925925925927
- (Sequential) linear-combination model mAP: 0.10851851851851851
- (Parallel) linear-combination model mAP: 0.10851851851851851
- (Distributed) linear-combination model mAP: 0.10851851851851851
- (Sequential) aggregation model model mAP: 0.10666666666666665
- (Parallel) aggregation model mAP: 0.10666666666666665
- (Distributed) aggregation model model mAP: 0.10666666666666665
- (Sequential) stochastic-combination model mAP: 0.08601851851851852
- (Parallel) stochastic-combination model mAP: 0.09388888888888888
- (Distributed) stochastic-combination model mAP: 0.08407407407407406

</details>

<details> 
<summary>Results with 300 train users and 10 test users</summary>

- Songs: 11660
- New songs: 299
- Elapsed time for (Sequential) user-based model:	499692ms (499692797759ns)
- Elapsed time for (Parallel) user-based model:	183963ms (183963880193ns)
- Elapsed time for (Distributed) user-based:	195862ms (195862459192ns)
- Elapsed time for (Sequential) item-based model:	313148ms (313148008407ns)
- Elapsed time for (Parallel) item-based model:	120420ms (120420783665ns)
- Elapsed time for (Distributed) item-based:	65393ms (65393364487ns)
- Elapsed time for (Sequential) linear-combination model:	60ms (60024649ns)
- Elapsed time for (Parallel) linear-combination model:	42ms (42212193ns)
- Elapsed time for (Distributed) linear combination:	666ms (666357856ns)
- Elapsed time for (Sequential) aggregation model:	79ms (79152312ns)
- Elapsed time for (Parallel) aggregation model:	52ms (52591492ns)
- Elapsed time for (Distributed) aggregation model:	811ms (811964429ns)
- Elapsed time for (Sequential) stochastic-combination model:	41ms (41112055ns)
- Elapsed time for (Parallel) stochastic-combination model:	151ms (151327529ns)
- Elapsed time for (Distributed) stochastic combination model:	664ms (664509917ns)
- Elapsed time for (Sequential) user-based model mAP:	625ms (625250557ns)
- Elapsed time for (Parallel) user-based model mAP:	259ms (259781739ns)
- Elapsed time for (Distributed) user-based model mAP:	639ms (639678238ns)
- Elapsed time for (Sequential) item-based model mAP:	1004ms (1004851920ns)
- Elapsed time for (Parallel) item-based model mAP:	527ms (527211348ns)
- Elapsed time for (Distributed) item-based model mAP:	1139ms (1139871017ns)
- Elapsed time for (Sequential) linear-combination model mAP:	1186ms (1186161832ns)
- Elapsed time for (Parallel) linear-combination model mAP:	401ms (401920738ns)
- Elapsed time for (Distributed) linear-combination model mAP:	770ms (770135834ns)
- Elapsed time for (Sequential) aggregation model mAP:	985ms (985416567ns)
- Elapsed time for (Parallel) aggregation model mAP:	352ms (352979137ns)
- Elapsed time for (Distributed) aggregation model mAP:	608ms (608555965ns)
- Elapsed time for (Sequential) stochastic-combination model mAP:	946ms (946619019ns)
- Elapsed time for (Parallel) stochastic-combination model mAP:	339ms (339079820ns)
- Elapsed time for (Distributed) stochastic-combination model mAP:	640ms (640502001ns)
- (Sequential) user-based model mAP: 0.15215798694059568
- (Parallel) user-based model mAP: 0.15215798694059568
- (Distributed) user-based model mAP: 0.15215798694059568
- (Sequential) item-based model mAP: 0.20493709189361367
- (Parallel) item-based model mAP: 0.20493709189361367
- (Distributed) item-based model mAP: 0.20493709189361367
- (Sequential) linear-combination model mAP: 0.20633062589584333
- (Parallel) linear-combination model mAP: 0.20633062589584333
- (Distributed) linear-combination model mAP: 0.20633062589584333
- (Sequential) aggregation model model mAP: 0.1652810957158784
- (Parallel) aggregation model mAP: 0.1652810957158784
- (Distributed) aggregation model model mAP: 0.1652810957158784
- (Sequential) stochastic-combination model mAP: 0.1794553272814143
- (Parallel) stochastic-combination model mAP: 0.18615225354355788
- (Distributed) stochastic-combination model mAP: 0.18465918139831183

</details>
