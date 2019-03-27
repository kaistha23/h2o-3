package ai.h2o.automl.targetencoding.strategy;

import ai.h2o.automl.AutoMLBenchmarkingHelper;
import ai.h2o.automl.targetencoding.TargetEncoder;
import ai.h2o.automl.targetencoding.TargetEncodingParams;
import ai.h2o.automl.targetencoding.TargetEncodingTestFixtures;
import hex.ModelBuilder;
import org.junit.BeforeClass;
import org.junit.Test;
import water.*;
import water.fvec.Frame;

import java.util.HashMap;

import static ai.h2o.automl.targetencoding.TargetEncodingTestFixtures.modelBuilderWithCVFixture;
import static ai.h2o.automl.targetencoding.TargetEncodingTestFixtures.modelBuilderGBMWithValidFrameFixture;
import static org.junit.Assert.*;

public class TargetEncodingHyperparamsEvaluatorTest extends TestUtil {

  @BeforeClass
  public static void setup() {
    stall_till_cloudsize(1);
  }

  @Test
  public void fixtureForMBTest() {

    Frame fr = parse_test_file("./smalldata/gbm_test/titanic.csv");
    String responseColumnName = "survived";

    asFactor(fr, responseColumnName);

    Frame[] splits = AutoMLBenchmarkingHelper.getRandomSplitsFromDataframe(fr, new double[]{0.7, 0.3}, 2345);
    Frame train = splits[0];
    Frame valid = splits[1];
    long builderSeed = 3456;
    ModelBuilder modelBuilder = modelBuilderGBMWithValidFrameFixture(train, valid, responseColumnName, builderSeed);

    modelBuilder.trainModel().get();
    assertTrue(modelBuilder.train().vec("Tree_0") == null);
    printOutFrameAsTable(modelBuilder.train());
  }

  // We test here that we can reuse model builder by cloning it.
  // Also we check that two evaluations with the same TE params return same result.
  @Test 
  public void evaluateMethodWorksWithModelBuilder() {
    Frame fr = null;
    Frame train = null;
    Frame valid = null;
    Frame leaderboard = null;
    ModelBuilder modelBuilder = null;
    try {
      fr = parse_test_file("./smalldata/gbm_test/titanic.csv");
      String responseColumnName = "survived";

      asFactor(fr, responseColumnName);

      Frame[] splits = AutoMLBenchmarkingHelper.getRandomSplitsFromDataframe(fr, new double[]{0.7, 0.15, 0.15}, 2345);
      train = splits[0];
      valid = splits[1];
      leaderboard = splits[2];

      TEApplicationStrategy strategy = new ThresholdTEApplicationStrategy(train, train.vec(responseColumnName), 4);

      TargetEncodingHyperparamsEvaluator evaluator = new TargetEncodingHyperparamsEvaluator();

      TargetEncodingParams randomTEParams = TargetEncodingTestFixtures.randomTEParams();
      long builderSeed = 3456;
      modelBuilder = modelBuilderGBMWithValidFrameFixture(train, valid, responseColumnName, builderSeed);
      String[] columnsToEncode = strategy.getColumnsToEncode();
      
      modelBuilder.init(false); //verifying that we can call init and then modify builder in evaluator
      ModelBuilder clonedModelBuilder = ModelBuilder.clone(modelBuilder);
      
      int seedForFoldColumn = 2345;
      double auc = evaluator.evaluate(randomTEParams, modelBuilder, ModelValidationMode.VALIDATION_FRAME, leaderboard, columnsToEncode, seedForFoldColumn);
      
      clonedModelBuilder.init(false);
      // checking that we can reuse modelBuilder
      double auc2 = evaluator.evaluate(randomTEParams, clonedModelBuilder, ModelValidationMode.VALIDATION_FRAME, leaderboard, columnsToEncode, seedForFoldColumn);
      assertTrue(isBitIdentical(clonedModelBuilder._parms.train(), modelBuilder._parms.train()));
      assertTrue(auc > 0);
      assertEquals(auc, auc2, 1e-5);
    } finally {
      if(fr!=null) fr.delete();
      if(train!=null) train.delete();
      if(leaderboard!=null) leaderboard.delete();
    }
  }


  @Test
  public void checkThatForAnyHyperParametersCombinationWeGetConsistentEvaluationsFromModelBuilderFixture() {

    //Important variable as AUC are not consistent with precision 1e-4 and less
    double precisionForAUCEvaluations = 1e-5;
    
    HashMap<String, Object[]> _grid = new HashMap<>();
    _grid.put("_withBlending", new Boolean[]{true, false});
    _grid.put("_noise_level", new Double[]{0.0, 0.1, 0.01});
    _grid.put("_inflection_point", new Integer[]{1, 2, 3, 5, 10, 50, 100});
    _grid.put("_smoothing", new Double[]{5.0, 10.0, 20.0});
    _grid.put("_holdoutType", new Byte[]{TargetEncoder.DataLeakageHandlingStrategy.LeaveOneOut,TargetEncoder.DataLeakageHandlingStrategy.KFold, TargetEncoder.DataLeakageHandlingStrategy.None});

    long testSeed = 2345; //TODO maybe -1?
    long builderSeed = 3456; 

    TargetEncodingHyperparamsEvaluator targetEncodingHyperparamsEvaluator = new TargetEncodingHyperparamsEvaluator();

    Frame fr = parse_test_file("./smalldata/gbm_test/titanic.csv");
    String responseColumnName = "survived";
    asFactor(fr, responseColumnName);
    ModelBuilder modelBuilder = modelBuilderWithCVFixture(fr, responseColumnName, builderSeed); // TODO try different model builders
    modelBuilder.init(false); // Should we init before cloning? Like in real use case we clone after initialisation of the original modelBuilder.
    TEApplicationStrategy strategy = new ThresholdTEApplicationStrategy(fr, fr.vec(responseColumnName), 4);


    for (int teParamAttempt = 0; teParamAttempt < 30; teParamAttempt++) {
      GridSearchTEParamsSelectionStrategy.RandomSelector randomSelector = new GridSearchTEParamsSelectionStrategy.RandomSelector(_grid, testSeed);
      GridSearchTEParamsSelectionStrategy.GridEntry selected = null;
      try {
        selected = randomSelector.getNext();
      } catch (GridSearchTEParamsSelectionStrategy.RandomSelector.GridSearchCompleted ex) {

      } 

      TargetEncodingParams param = new TargetEncodingParams(selected.getItem());
      TargetEncodingParams tmpParam  = new TargetEncodingParams(null, TargetEncoder.DataLeakageHandlingStrategy.LeaveOneOut, 0.0);


      double lastResult = 0.0;
      for (int evaluationAttempt = 0; evaluationAttempt < 50; evaluationAttempt++) {
        ModelBuilder clonedModelBuilder = ModelBuilder.clone(modelBuilder);
        clonedModelBuilder.init(false);
        
        double evaluationResult = targetEncodingHyperparamsEvaluator.evaluate(tmpParam, clonedModelBuilder, ModelValidationMode.VALIDATION_FRAME, null, strategy.getColumnsToEncode(), testSeed);
        if(lastResult == 0.0) lastResult = evaluationResult;
        else {
          
          assertEquals("evaluationAttempt #" + evaluationAttempt + " for teParamAttempt #" + 
                  teParamAttempt + " has failed. " + tmpParam , lastResult, evaluationResult, precisionForAUCEvaluations);
        }
      }
    }
    
  }
  
  @Test
  public void evaluateMethodWorksWithModelBuilderAndIgnoredColumns() {
    //TODO check case when original model builder has ignored columns
  }
  
 /* @Test
  public void evaluateMethodDoesNotLeakKeys() {
    Frame fr = null;
    try {
      fr = parse_test_file("./smalldata/gbm_test/titanic.csv");

      long seedForFoldColumn = 2345L;
      final String foldColumnForTE = "custom_fold";
      int nfolds = 5;
      addKFoldColumn(fr, foldColumnForTE, nfolds, seedForFoldColumn);

      String responseColumnName = "survived";

      asFactor(fr, responseColumnName);

      TEApplicationStrategy strategy = new ThresholdTEApplicationStrategy(fr, fr.vec("survived"), 4);

      TargetEncodingHyperparamsEvaluator evaluator = new TargetEncodingHyperparamsEvaluator();

      TargetEncodingParams anyParams = TargetEncodingTestFixtures.defaultTEParams();
      evaluator.evaluate(anyParams, new Algo[]{Algo.GBM}, fr, responseColumnName, foldColumnForTE, strategy.getColumnsToEncode());
    } finally {
      fr.delete();
    }
  }*/
  
}
