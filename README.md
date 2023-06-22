# Music Recommendation
Repository for the project of the Scalable and Cloud Computing course @unibo, a.y. 2022-23.

Inspired by the Million Song Dataset challenge on Kaggle, a comparison of various solutions to the problem
exploiting the MapReduce paradigm and providing an environment for sequential, parallel and distributed
execution. Hence, we aim to provide not only a comparison between the different algorithms, but also an
analysis of the behaviours of these algorithms across the different executions.

The different algorithms produced can be found in the `src/main/scala/` directory as scala worksheet
files. To execute the algorithms, one should download the <b>Echo Nest Taste Profile Subset</b> dataset from
http://millionsongdataset.com/tasteprofile/ and place the `train_triplets.txt` file under the `src/main/resources/`
directory (we advise working on a subset of it; `dataAnalysis.ipynb` can be an useful tool to do that).
Once executed, the results of the training will be available through a text file with the same name of the
algorithm which can be found under `target/scala-2.12/classes/models/`. Make sure to also have an empty file in
`src/main/resources/models/` with the same name of the model (in case not, create it, as it will be used to dynamically
create the path for the output model, which will still be found under the `target/scala-2.12/classes/models/` path).

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

### Google Cloud Platform Results
- Sequential:

| TrainUsers | TestUsers | Songs | NewSongs | ubm     | ibm    |
|------------|-----------|-------|----------|---------|--------|
| 100        | 10        | 4798  | 262      | 97656   | 72911  |
| 200        | 10        | 8763  | 180      | 269930  | 152921 |
| 300        | 10        | 11660 | 299      | 733888  | 486744 |

- Parallel:

| TrainUsers | TestUsers | Songs | NewSongs | ubm     | ibm     |
|------------|-----------|-------|----------|---------|---------|
| 100        | 10        | 4798  | 262      | 44816   | 36954   |
| 200        | 10        | 8763  | 180      | 117920  | 76920   |
| 300        | 10        | 11660 | 299      | 326719  | 244839  |

<details>
	<summary>Google Cloud Platform results with 100 train users and 10 test users, sequential and parallel</summary>

* Elapsed time for (Sequential) user-based model:	97656ms (97656685124ns)
* Elapsed time for (Parallel) user-based model:	44816ms (44816864126ns)
* Elapsed time for (Sequential) item-based model:	72911ms (72911158507ns)
* Elapsed time for (Parallel) item-based model:	36954ms (36954270883ns)
* Elapsed time for (Sequential) linear-combination model:	37ms (37571899ns)
* Elapsed time for (Parallel) linear-combination model:	38ms (38456368ns)
* Elapsed time for (Sequential) aggregation model:	47ms (47790224ns)
* Elapsed time for (Parallel) aggregation model:	44ms (44845385ns)
* Elapsed time for (Sequential) stochastic-combination model:	37ms (37758623ns)
* Elapsed time for (Parallel) stochastic-combination model:	29ms (29963087ns)
* Elapsed time for (Sequential) user-based model mAP:	527ms (527130491ns)
* Elapsed time for (Parallel) user-based model mAP:	173ms (173851283ns)
* Elapsed time for (Sequential) item-based model mAP:	507ms (507504337ns)
* Elapsed time for (Parallel) item-based model mAP:	277ms (277799990ns)
* Elapsed time for (Sequential) linear-combination model mAP:	471ms (471674444ns)
* Elapsed time for (Parallel) linear-combination model mAP:	327ms (327742593ns)
* Elapsed time for (Sequential) aggregation model mAP:	394ms (394096201ns)
* Elapsed time for (Parallel) aggregation model mAP:	225ms (225993668ns)
* Elapsed time for (Sequential) stochastic-combination model mAP:	342ms (342695888ns)
* Elapsed time for (Parallel) stochastic-combination model mAP:	219ms (219828910ns)
* (Sequential) user-based model mAP: 0.06180479825517996
* (Parallel) user-based model mAP: 0.06180479825517996
* (Sequential) item-based model mAP: 0.09904580152671755
* (Parallel) item-based model mAP: 0.09904580152671755
* (Sequential) linear-combination model mAP: 0.1025445292620865
* (Parallel) linear-combination model mAP: 0.1025445292620865
* (Sequential) aggregation model model mAP: 0.06371319520174483
* (Parallel) aggregation model mAP: 0.06371319520174483
* (Sequential) stochastic-combination model mAP: 0.0669211195928753
* (Parallel) stochastic-combination model mAP: 0.08438749545619774

</details>


<details>
	<summary>Google Cloud Platform results with 100 train users and 10 test users, distributed (using getUserBasedModel1)</summary>

* Elapsed time for (Distributed) user-based:	44743ms (44743600646ns)
* Elapsed time for (Distributed) item-based:	8325ms (8325064033ns)
* Elapsed time for (Distributed) linear combination:	811ms (811810328ns)
* Elapsed time for (Distributed) aggregation model:	783ms (783823862ns)
* Elapsed time for (Distributed) stochastic combination model:	592ms (592120863ns)
* Elapsed time for (Distributed) user-based model mAP:	739ms (739456872ns)
* Elapsed time for (Distributed) item-based model mAP:	792ms (792616685ns)
* Elapsed time for (Distributed) linear-combination model mAP:	427ms (427608239ns)
* Elapsed time for (Distributed) aggregation model mAP:	434ms (434124987ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	365ms (365387619ns)
* (Distributed) user-based model mAP: 0.06180479825517996
* (Distributed) item-based model mAP: 0.09904580152671755
* (Distributed) linear-combination model mAP: 0.1025445292620865
* (Distributed) aggregation model model mAP: 0.06371319520174483
* (Distributed) stochastic-combination model mAP: 0.07529080334423846

</details>

<details>
	<summary>Google Cloud Platform results with 100 train users and 10 test users, distributed (using getUserModelBased2)</summary>

* Songs: 4798
* New songs: 262
* Elapsed time for (Distributed) user-based:	67065ms (67065735276ns)
* Elapsed time for (Distributed) item-based:	10232ms (10232465414ns)
* Elapsed time for (Distributed) linear combination:	773ms (773137661ns)
* Elapsed time for (Distributed) aggregation model:	530ms (530372378ns)
* Elapsed time for (Distributed) stochastic combination model:	539ms (539616492ns)
* Elapsed time for (Distributed) user-based model mAP:	640ms (640509589ns)
* Elapsed time for (Distributed) item-based model mAP:	647ms (647724442ns)
* Elapsed time for (Distributed) linear-combination model mAP:	490ms (490459063ns)
* Elapsed time for (Distributed) aggregation model mAP:	384ms (384074222ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	413ms (413616697ns)
* (Distributed) user-based model mAP: 0.06180479825517996
* (Distributed) item-based model mAP: 0.09904580152671755
* (Distributed) linear-combination model mAP: 0.1025445292620865
* (Distributed) aggregation model model mAP: 0.06371319520174483
* (Distributed) stochastic-combination model mAP: 0.07497273718647765

</details>

<details>
	<summary>Google Cloud Platform results with 100 train users and 50 test users, sequential and parallel</summary>

* Elapsed time for (Sequential) user-based model:	611534ms (611534251612ns)
* Elapsed time for (Parallel) user-based model:	285879ms (285879251193ns)
* Elapsed time for (Sequential) item-based model:	478689ms (478689837874ns)
* Elapsed time for (Parallel) item-based model:	250850ms (250850176272ns)
* Elapsed time for (Sequential) linear-combination model:	130ms (130684096ns)
* Elapsed time for (Parallel) linear-combination model:	312ms (312002847ns)
* Elapsed time for (Sequential) aggregation model:	155ms (155102764ns)
* Elapsed time for (Parallel) aggregation model:	247ms (247342251ns)
* Elapsed time for (Sequential) stochastic-combination model:	89ms (89028839ns)
* Elapsed time for (Parallel) stochastic-combination model:	256ms (256374564ns)
* Elapsed time for (Sequential) user-based model mAP:	6172ms (6172663825ns)
* Elapsed time for (Parallel) user-based model mAP:	3536ms (3536665059ns)
* Elapsed time for (Sequential) item-based model mAP:	6700ms (6700079706ns)
* Elapsed time for (Parallel) item-based model mAP:	3712ms (3712345993ns)
* Elapsed time for (Sequential) linear-combination model mAP:	8857ms (8857151666ns)
* Elapsed time for (Parallel) linear-combination model mAP:	3765ms (3765745056ns)
* Elapsed time for (Sequential) aggregation model mAP:	10380ms (10380005636ns)
* Elapsed time for (Parallel) aggregation model mAP:	4689ms (4689010311ns)
* Elapsed time for (Sequential) stochastic-combination model mAP:	7333ms (7333332994ns)
* Elapsed time for (Parallel) stochastic-combination model mAP:	3418ms (3418810652ns)
* (Sequential) user-based model mAP: 0.03201406830127337
* (Parallel) user-based model mAP: 0.03201406830127337
* (Sequential) item-based model mAP: 0.07304353273841559
* (Parallel) item-based model mAP: 0.07304353273841559
* (Sequential) linear-combination model mAP: 0.07322915733221809
* (Parallel) linear-combination model mAP: 0.07322915733221809
* (Sequential) aggregation model model mAP: 0.04614277953880507
* (Parallel) aggregation model mAP: 0.04614277953880507
* (Sequential) stochastic-combination model mAP: 0.05347576332061793
* (Parallel) stochastic-combination model mAP: 0.051828129862320604

</details>

<details>
	<summary>Google Cloud Platform results with 100 train users and 50 test users, distributed (using getUserBasedModel1)</summary>

* Elapsed time for (Distributed) user-based:	275304ms (275304611902ns)
* Elapsed time for (Distributed) item-based:	77195ms (77195600361ns)
* Elapsed time for (Distributed) linear combination:	3735ms (3735419010ns)
* Elapsed time for (Distributed) aggregation model:	3937ms (3937820611ns)
* Elapsed time for (Distributed) stochastic combination model:	3071ms (3071226589ns)
* Elapsed time for (Distributed) user-based model mAP:	4861ms (4861509324ns)
* Elapsed time for (Distributed) item-based model mAP:	5246ms (5246929176ns)
* Elapsed time for (Distributed) linear-combination model mAP:	4785ms (4785118805ns)
* Elapsed time for (Distributed) aggregation model mAP:	5354ms (5354537570ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	4117ms (4117424480ns)
* (Distributed) user-based model mAP: 0.03201406830127337
* (Distributed) item-based model mAP: 0.07304353273841559
* (Distributed) linear-combination model mAP: 0.07322915733221809
* (Distributed) aggregation model model mAP: 0.04614277953880507
* (Distributed) stochastic-combination model mAP: 0.048424830970757414

</details>

<details>
	<summary>Google Cloud Platform results with 100 train users and 50 test users, distributed (using getUserModelBased2)</summary>

Songs: 5679
New songs: 1394
Elapsed time for (Distributed) user-based:	320513ms (320513114111ns)
Elapsed time for (Distributed) item-based:	84498ms (84498835609ns)
Elapsed time for (Distributed) linear combination:	3172ms (3172884583ns)
Elapsed time for (Distributed) aggregation model:	3355ms (3355378780ns)
Elapsed time for (Distributed) stochastic combination model:	2857ms (2857173891ns)
Elapsed time for (Distributed) user-based model mAP:	5007ms (5007358135ns)
Elapsed time for (Distributed) item-based model mAP:	5512ms (5512477957ns)
Elapsed time for (Distributed) linear-combination model mAP:	5328ms (5328499297ns)
Elapsed time for (Distributed) aggregation model mAP:	6086ms (6086952447ns)
Elapsed time for (Distributed) stochastic-combination model mAP:	4667ms (4667140478ns)
(Distributed) user-based model mAP: 0.03201406830127337
(Distributed) item-based model mAP: 0.07304353273841559
(Distributed) linear-combination model mAP: 0.07322915733221809
(Distributed) aggregation model model mAP: 0.04614277953880507
(Distributed) stochastic-combination model mAP: 0.05216363018558082
</details>

<details>
	<summary>Google Cloud Platform results with 100 train users and 100 test users, sequential and parallel</summary>

* Elapsed time for (Sequential) user-based model:	1358665ms (1358665826513ns)
* Elapsed time for (Parallel) user-based model:	650901ms (650901676564ns)
* Elapsed time for (Sequential) item-based model:	1217938ms (1217938703125ns)
* Elapsed time for (Parallel) item-based model:	708828ms (708828860237ns)
* Elapsed time for (Sequential) linear-combination model:	252ms (252637200ns)
* Elapsed time for (Parallel) linear-combination model:	487ms (487847333ns)
* Elapsed time for (Sequential) aggregation model:	402ms (402969812ns)
* Elapsed time for (Parallel) aggregation model:	565ms (565774528ns)
* Elapsed time for (Sequential) stochastic-combination model:	357ms (357554146ns)
* Elapsed time for (Parallel) stochastic-combination model:	165ms (165071371ns)
* Elapsed time for (Sequential) user-based model mAP:	18605ms (18605357797ns)
* Elapsed time for (Parallel) user-based model mAP:	10059ms (10059377598ns)
* Elapsed time for (Sequential) item-based model mAP:	19432ms (19432374211ns)
* Elapsed time for (Parallel) item-based model mAP:	10241ms (10241680357ns)
* Elapsed time for (Sequential) linear-combination model mAP:	31952ms (31952068691ns)
* Elapsed time for (Parallel) linear-combination model mAP:	12285ms (12285477386ns)
* Elapsed time for (Sequential) aggregation model mAP:	34107ms (34107116620ns)
* Elapsed time for (Parallel) aggregation model mAP:	14129ms (14129190126ns)
* Elapsed time for (Sequential) stochastic-combination model mAP:	28680ms (28680583825ns)
* Elapsed time for (Parallel) stochastic-combination model mAP:	11246ms (11246769410ns)
* (Sequential) user-based model mAP: 0.016535498602550824
* (Parallel) user-based model mAP: 0.016535498602550824
* (Sequential) item-based model mAP: 0.03945161744725745
* (Parallel) item-based model mAP: 0.03945161744725745
* (Sequential) linear-combination model mAP: 0.04045516217984957
* (Parallel) linear-combination model mAP: 0.04045516217984957
* (Sequential) aggregation model model mAP: 0.02603719801265665
* (Parallel) aggregation model mAP: 0.02603719801265665
* (Sequential) stochastic-combination model mAP: 0.026280351505641366
* (Parallel) stochastic-combination model mAP: 0.031865372956558204

</details>

<details>
	<summary>Google Cloud Platform results with 100 train users and 100 test users, distributed (using getUserBasedModel1)</summary>

* Elapsed time for (Distributed) user-based:	751859ms (751859562625ns)
* Elapsed time for (Distributed) item-based:	212092ms (212092089123ns)
* Elapsed time for (Distributed) linear combination:	8254ms (8254892491ns)
* Elapsed time for (Distributed) aggregation model:	8883ms (8883583261ns)
* Elapsed time for (Distributed) stochastic combination model:	7005ms (7005066655ns)
* Elapsed time for (Distributed) user-based model mAP:	14458ms (14458080426ns)
* Elapsed time for (Distributed) item-based model mAP:	15344ms (15344816270ns)
* Elapsed time for (Distributed) linear-combination model mAP:	16151ms (16151675339ns)
* Elapsed time for (Distributed) aggregation model mAP:	18000ms (18000375733ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	14284ms (14284633786ns)
* (Distributed) user-based model mAP: 0.016535498602550824
* (Distributed) item-based model mAP: 0.03945161744725745
* (Distributed) linear-combination model mAP: 0.04045516217984957
* (Distributed) aggregation model model mAP: 0.02603719801265665
* (Distributed) stochastic-combination model mAP: 0.026528658029230125

</details>

<details>
	<summary>Google Cloud Platform results with 100 train users and 100 test users, distributed (using getUserModelBased2)</summary>

* Songs: 6620
* New songs: 2480
* Elapsed time for (Distributed) user-based:	578647ms (578647467076ns)
* Elapsed time for (Distributed) item-based:	206604ms (206604196528ns)
* Elapsed time for (Distributed) linear combination:	8712ms (8712165509ns)
* Elapsed time for (Distributed) aggregation model:	9039ms (9039501258ns)
* Elapsed time for (Distributed) stochastic combination model:	7079ms (7079955855ns)
* Elapsed time for (Distributed) user-based model mAP:	12321ms (12321631541ns)
* Elapsed time for (Distributed) item-based model mAP:	12360ms (12360277088ns)
* Elapsed time for (Distributed) linear-combination model mAP:	12459ms (12459282332ns)
* Elapsed time for (Distributed) aggregation model mAP:	14409ms (14409851852ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	11668ms (11668739496ns)
* (Distributed) user-based model mAP: 0.016535498602550824
* (Distributed) item-based model mAP: 0.03945161744725745
* (Distributed) linear-combination model mAP: 0.04045516217984957
* (Distributed) aggregation model model mAP: 0.02603719801265665
* (Distributed) stochastic-combination model mAP: 0.026648181787622804

</details>


<details>
	<summary>Google Cloud Platform results with 200 train users and 10 test users, sequential and parallel</summary>

* Elapsed time for (Sequential) user-based model:	269930ms (269930847228ns)
* Elapsed time for (Parallel) user-based model:	117920ms (117920949587ns)
* Elapsed time for (Sequential) item-based model:	152921ms (152921859507ns)
* Elapsed time for (Parallel) item-based model:	76920ms (76920003398ns)
* Elapsed time for (Sequential) linear-combination model:	83ms (83969009ns)
* Elapsed time for (Parallel) linear-combination model:	84ms (84280334ns)
* Elapsed time for (Sequential) aggregation model:	88ms (88240756ns)
* Elapsed time for (Parallel) aggregation model:	92ms (92708858ns)
* Elapsed time for (Sequential) stochastic-combination model:	59ms (59836236ns)
* Elapsed time for (Parallel) stochastic-combination model:	89ms (89425776ns)
* Elapsed time for (Sequential) user-based model mAP:	512ms (512400457ns)
* Elapsed time for (Parallel) user-based model mAP:	223ms (223943646ns)
* Elapsed time for (Sequential) item-based model mAP:	644ms (644745357ns)
* Elapsed time for (Parallel) item-based model mAP:	418ms (418664663ns)
* Elapsed time for (Sequential) linear-combination model mAP:	686ms (686942689ns)
* Elapsed time for (Parallel) linear-combination model mAP:	345ms (345327529ns)
* Elapsed time for (Sequential) aggregation model mAP:	448ms (448796775ns)
* Elapsed time for (Parallel) aggregation model mAP:	317ms (317684010ns)
* Elapsed time for (Sequential) stochastic-combination model mAP:	493ms (493693527ns)
* Elapsed time for (Parallel) stochastic-combination model mAP:	277ms (277757677ns)
* (Sequential) user-based model mAP: 0.06740740740740742
* (Parallel) user-based model mAP: 0.06740740740740742
* (Sequential) item-based model mAP: 0.11175925925925927
* (Parallel) item-based model mAP: 0.11175925925925927
* (Sequential) linear-combination model mAP: 0.10851851851851851
* (Parallel) linear-combination model mAP: 0.10851851851851851
* (Sequential) aggregation model model mAP: 0.10666666666666665
* (Parallel) aggregation model mAP: 0.10666666666666665
* (Sequential) stochastic-combination model mAP: 0.08416666666666667
* (Parallel) stochastic-combination model mAP: 0.1037962962962963

</details>


<details>
	<summary>Google Cloud Platform results with 200 train users and 10 test users, distributed (using getUserBasedModel1)</summary>

* Elapsed time for (Distributed) user-based:	109909ms (109909938911ns)
* Elapsed time for (Distributed) item-based:	41832ms (41832081661ns)
* Elapsed time for (Distributed) linear combination:	1567ms (1567537347ns)
* Elapsed time for (Distributed) aggregation model:	1610ms (1610029809ns)
* Elapsed time for (Distributed) stochastic combination model:	1030ms (1030877120ns)
* Elapsed time for (Distributed) user-based model mAP:	1006ms (1006755997ns)
* Elapsed time for (Distributed) item-based model mAP:	1406ms (1406950294ns)
* Elapsed time for (Distributed) linear-combination model mAP:	653ms (653066881ns)
* Elapsed time for (Distributed) aggregation model mAP:	635ms (635224674ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	699ms (699665423ns)
* (Distributed) user-based model mAP: 0.06740740740740742
* (Distributed) item-based model mAP: 0.11175925925925927
* (Distributed) linear-combination model mAP: 0.10851851851851851
* (Distributed) aggregation model model mAP: 0.10666666666666665
* (Distributed) stochastic-combination model mAP: 0.0875925925925926

</details>


<details>
	<summary>Google Cloud Platform results with 200 train users and 10 test users, distributed (using getUserBasedModel2)</summary>


* Elapsed time for (Distributed) user-based:	126247ms (126247077182ns)
* Elapsed time for (Distributed) item-based:	37870ms (37870976002ns)
* Elapsed time for (Distributed) linear combination:	1446ms (1446532947ns)
* Elapsed time for (Distributed) aggregation model:	1216ms (1216458050ns)
* Elapsed time for (Distributed) stochastic combination model:	998ms (998215363ns)
* Elapsed time for (Distributed) user-based model mAP:	1045ms (1045137054ns)
* Elapsed time for (Distributed) item-based model mAP:	1102ms (1102919151ns)
* Elapsed time for (Distributed) linear-combination model mAP:	640ms (640206199ns)
* Elapsed time for (Distributed) aggregation model mAP:	531ms (531250577ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	598ms (598308847ns)
* (Distributed) user-based model mAP: 0.06740740740740742
* (Distributed) item-based model mAP: 0.11175925925925927
* (Distributed) linear-combination model mAP: 0.10851851851851851
* (Distributed) aggregation model model mAP: 0.10666666666666665
* (Distributed) stochastic-combination model mAP: 0.1012962962962963

</details>

<details>
	<summary>Google Cloud Platform results with 300 train users and 10 test users, sequential and parallel</summary>

* Elapsed time for (Sequential) user-based model:	733888ms (733888407032ns)
* Elapsed time for (Parallel) user-based model:	326719ms (326719483813ns)
* Elapsed time for (Sequential) item-based model:	486744ms (486744092423ns)
* Elapsed time for (Parallel) item-based model:	244839ms (244839905591ns)
* Elapsed time for (Sequential) linear-combination model:	80ms (80890148ns)
* Elapsed time for (Parallel) linear-combination model:	97ms (97136594ns)
* Elapsed time for (Sequential) aggregation model:	283ms (283943312ns)
* Elapsed time for (Parallel) aggregation model:	61ms (61058169ns)
* Elapsed time for (Sequential) stochastic-combination model:	42ms (42710757ns)
* Elapsed time for (Parallel) stochastic-combination model:	87ms (87189536ns)
* Elapsed time for (Sequential) user-based model mAP:	937ms (937934531ns)
* Elapsed time for (Parallel) user-based model mAP:	401ms (401358159ns)
* Elapsed time for (Sequential) item-based model mAP:	1368ms (1368988225ns)
* Elapsed time for (Parallel) item-based model mAP:	1093ms (1093769758ns)
* Elapsed time for (Sequential) linear-combination model mAP:	1363ms (1363201828ns)
* Elapsed time for (Parallel) linear-combination model mAP:	626ms (626647212ns)
* Elapsed time for (Sequential) aggregation model mAP:	1075ms (1075983365ns)
* Elapsed time for (Parallel) aggregation model mAP:	585ms (585796072ns)
* Elapsed time for (Sequential) stochastic-combination model mAP:	1043ms (1043088923ns)
* Elapsed time for (Parallel) stochastic-combination model mAP:	602ms (602521138ns)
* (Sequential) user-based model mAP: 0.15215798694059568
* (Parallel) user-based model mAP: 0.15215798694059568
* (Sequential) item-based model mAP: 0.20493709189361367
* (Parallel) item-based model mAP: 0.20493709189361367
* (Sequential) linear-combination model mAP: 0.20633062589584333
* (Parallel) linear-combination model mAP: 0.20633062589584333
* (Sequential) aggregation model model mAP: 0.1652810957158784
* (Parallel) aggregation model mAP: 0.1652810957158784
* (Sequential) stochastic-combination model mAP: 0.1717510750119446
* (Parallel) stochastic-combination model mAP: 0.19073897117375385
</details>

<details>
	<summary>Google Cloud Platform results with 300 train users and 10 test users, distributed (using getUserBasedModel1)</summary>

* Elapsed time for (Distributed) user-based:	295800ms (295800913942ns)
* Elapsed time for (Distributed) item-based:	89073ms (89073970124ns)
* Elapsed time for (Distributed) linear combination:	2117ms (2117285343ns)
* Elapsed time for (Distributed) aggregation model:	1939ms (1939571622ns)
* Elapsed time for (Distributed) stochastic combination model:	1543ms (1543588465ns)
* Elapsed time for (Distributed) user-based model mAP:	1175ms (1175365583ns)
* Elapsed time for (Distributed) item-based model mAP:	1328ms (1328293644ns)
* Elapsed time for (Distributed) linear-combination model mAP:	981ms (981051353ns)
* Elapsed time for (Distributed) aggregation model mAP:	818ms (818356355ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	939ms (939888069ns)
* (Distributed) user-based model mAP: 0.15215798694059568
* (Distributed) item-based model mAP: 0.20493709189361367
* (Distributed) linear-combination model mAP: 0.20633062589584333
* (Distributed) aggregation model model mAP: 0.1652810957158784
* (Distributed) stochastic-combination model mAP: 0.18412565695174396

</details>


<details>
	<summary>Google Cloud Platform results with 300 train users and 10 test users, distributed (using getUserBasedModel2)</summary>

* Elapsed time for (Distributed) user-based:	321473ms (321473377834ns)
* Elapsed time for (Distributed) item-based:	87412ms (87412030850ns)
* Elapsed time for (Distributed) linear combination:	1768ms (1768441960ns)
* Elapsed time for (Distributed) aggregation model:	1940ms (1940175141ns)
* Elapsed time for (Distributed) stochastic combination model:	1556ms (1556340749ns)
* Elapsed time for (Distributed) user-based model mAP:	1236ms (1236883751ns)
* Elapsed time for (Distributed) item-based model mAP:	1642ms (1642492160ns)
* Elapsed time for (Distributed) linear-combination model mAP:	962ms (962868484ns)
* Elapsed time for (Distributed) aggregation model mAP:	879ms (879069745ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	923ms (923261556ns)
* (Distributed) user-based model mAP: 0.15215798694059568
* (Distributed) item-based model mAP: 0.20493709189361367
* (Distributed) linear-combination model mAP: 0.20633062589584333
* (Distributed) aggregation model model mAP: 0.1652810957158784
* (Distributed) stochastic-combination model mAP: 0.18721930243669377
</details>

<details>
	<summary>Google Cloud Platform results with 400 train users and 10 test users, sequential and parallel</summary>

* Elapsed time for (Sequential) user-based model:	936666ms (936666484780ns)
* Elapsed time for (Parallel) user-based model:	407006ms (407006348156ns)
* Elapsed time for (Sequential) item-based model:	482225ms (482225037295ns)
* Elapsed time for (Parallel) item-based model:	226117ms (226117208140ns)
* Elapsed time for (Sequential) linear-combination model:	99ms (99138721ns)
* Elapsed time for (Parallel) linear-combination model:	82ms (82724376ns)
* Elapsed time for (Sequential) aggregation model:	123ms (123882578ns)
* Elapsed time for (Parallel) aggregation model:	135ms (135690204ns)
* Elapsed time for (Sequential) stochastic-combination model:	69ms (69006215ns)
* Elapsed time for (Parallel) stochastic-combination model:	69ms (69384342ns)
* Elapsed time for (Sequential) user-based model mAP:	737ms (737148761ns)
* Elapsed time for (Parallel) user-based model mAP:	488ms (488886621ns)
* Elapsed time for (Sequential) item-based model mAP:	1151ms (1151803116ns)
* Elapsed time for (Parallel) item-based model mAP:	594ms (594065546ns)
* Elapsed time for (Sequential) linear-combination model mAP:	1009ms (1009290363ns)
* Elapsed time for (Parallel) linear-combination model mAP:	504ms (504183422ns)
* Elapsed time for (Sequential) aggregation model mAP:	655ms (655627148ns)
* Elapsed time for (Parallel) aggregation model mAP:	424ms (424498478ns)
* Elapsed time for (Sequential) stochastic-combination model mAP:	731ms (731564845ns)
* Elapsed time for (Parallel) stochastic-combination model mAP:	398ms (398262288ns)
* (Sequential) user-based model mAP: 0.14360983102918587
* (Parallel) user-based model mAP: 0.14360983102918587
* (Sequential) item-based model mAP: 0.19683563748079874
* (Parallel) item-based model mAP: 0.19683563748079874
* (Sequential) linear-combination model mAP: 0.20113671274961595
* (Parallel) linear-combination model mAP: 0.20113671274961595
* (Sequential) aggregation model model mAP: 0.13124423963133638
* (Parallel) aggregation model mAP: 0.13124423963133638
* (Sequential) stochastic-combination model mAP: 0.1386175115207373
* (Parallel) stochastic-combination model mAP: 0.1774807987711213

</details>

<details>
	<summary>Google Cloud Platform results with 400 train users and 10 test users, distributed (using getUserModelBased1)</summary>

* Songs: 14497
* New songs: 155
* Elapsed time for (Distributed) user-based:	266245ms (266245630849ns)
* Elapsed time for (Distributed) item-based:	140151ms (140151785753ns)
* Elapsed time for (Distributed) linear combination:	2099ms (2099071427ns)
* Elapsed time for (Distributed) aggregation model:	2264ms (2264814813ns)
* Elapsed time for (Distributed) stochastic combination model:	1585ms (1585114740ns)
* Elapsed time for (Distributed) user-based model mAP:	1597ms (1597770414ns)
* Elapsed time for (Distributed) item-based model mAP:	1671ms (1671029945ns)
* Elapsed time for (Distributed) linear-combination model mAP:	832ms (832420599ns)
* Elapsed time for (Distributed) aggregation model mAP:	723ms (723748003ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	729ms (729386219ns)
* (Distributed) user-based model mAP: 0.14360983102918587
* (Distributed) item-based model mAP: 0.19683563748079874
* (Distributed) linear-combination model mAP: 0.20113671274961595
* (Distributed) aggregation model model mAP: 0.13124423963133638
* (Distributed) stochastic-combination model mAP: 0.17264208909370196

</details>

<details>
	<summary>Google Cloud Platform results with 500 train users and 10 test users, sequential and parallel</summary>

* Elapsed time for (Sequential) user-based model:	2205967ms (2205967360468ns)
* Elapsed time for (Parallel) user-based model:	877356ms (877356886174ns)
* Elapsed time for (Sequential) item-based model:	734849ms (734849894495ns)
* Elapsed time for (Parallel) item-based model:	375472ms (375472918112ns)
* Elapsed time for (Sequential) linear-combination model:	190ms (190434851ns)
* Elapsed time for (Parallel) linear-combination model:	203ms (203098959ns)
* Elapsed time for (Sequential) aggregation model:	109ms (109966160ns)
* Elapsed time for (Parallel) aggregation model:	95ms (95520017ns)
* Elapsed time for (Sequential) stochastic-combination model:	77ms (77233973ns)
* Elapsed time for (Parallel) stochastic-combination model:	72ms (72809152ns)
* Elapsed time for (Sequential) user-based model mAP:	1178ms (1178607589ns)
* Elapsed time for (Parallel) user-based model mAP:	686ms (686682599ns)
* Elapsed time for (Sequential) item-based model mAP:	1238ms (1238750163ns)
* Elapsed time for (Parallel) item-based model mAP:	820ms (820550694ns)
* Elapsed time for (Sequential) linear-combination model mAP:	1368ms (1368495643ns)
* Elapsed time for (Parallel) linear-combination model mAP:	647ms (647808253ns)
* Elapsed time for (Sequential) aggregation model mAP:	1311ms (1311540718ns)
* Elapsed time for (Parallel) aggregation model mAP:	740ms (740314681ns)
* Elapsed time for (Sequential) stochastic-combination model mAP:	1185ms (1185025903ns)
* Elapsed time for (Parallel) stochastic-combination model mAP:	603ms (603489295ns)
* (Sequential) user-based model mAP: 0.16613160291438983
* (Parallel) user-based model mAP: 0.16613160291438983
* (Sequential) item-based model mAP: 0.28097677595628423
* (Parallel) item-based model mAP: 0.28097677595628423
* (Sequential) linear-combination model mAP: 0.2721116315378611
* (Parallel) linear-combination model mAP: 0.2721116315378611
* (Sequential) aggregation model model mAP: 0.2748162243039293
* (Parallel) aggregation model mAP: 0.2748162243039293
* (Sequential) stochastic-combination model mAP: 0.21558840749414526
* (Parallel) stochastic-combination model mAP: 0.23189565443663812

</details>

<details>
	<summary>Google Cloud Platform results with 500 train users and 10 test users, distributed (using getUserModelBased1)</summary>

* Songs: 16785
* New songs: 244
* Elapsed time for (Distributed) user-based:	602269ms (602269930660ns)
* 23/06/21 10:47:51 WARN DAGScheduler: Broadcasting large task binary with size 1689.6 KiB
* Elapsed time for (Distributed) item-based:	217169ms (217169123823ns)
* 23/06/21 10:51:29 WARN TaskSetManager: Stage 2 contains a task of very large size (7436 KiB). The maximum recommended task size is 1000 KiB.
* Elapsed time for (Distributed) linear combination:	2061ms (2061734804ns)
* 23/06/21 10:51:31 WARN TaskSetManager: Stage 3 contains a task of very large size (8745 KiB). The maximum recommended task size is 1000 KiB.
* Elapsed time for (Distributed) aggregation model:	2380ms (2380013561ns)
* 23/06/21 10:51:33 WARN TaskSetManager: Stage 4 contains a task of very large size (7436 KiB). The maximum recommended task size is 1000 KiB.
* Elapsed time for (Distributed) stochastic combination model:	1954ms (1954185593ns)
* Elapsed time for (Distributed) user-based model mAP:	1997ms (1997671937ns)
* Elapsed time for (Distributed) item-based model mAP:	1599ms (1599392001ns)
* Elapsed time for (Distributed) linear-combination model mAP:	952ms (952811705ns)
* Elapsed time for (Distributed) aggregation model mAP:	897ms (897861002ns)
* Elapsed time for (Distributed) stochastic-combination model mAP:	915ms (915438956ns)
* (Distributed) user-based model mAP: 0.16613160291438983
* (Distributed) item-based model mAP: 0.28097677595628423
* (Distributed) linear-combination model mAP: 0.2721116315378611
* (Distributed) aggregation model model mAP: 0.2748162243039293
* (Distributed) stochastic-combination model mAP: 0.22365502211813698

</details>
