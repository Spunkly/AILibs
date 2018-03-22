package de.upb.crc901.mlplan.search.evaluators;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Range;

import de.upb.crc901.mlplan.core.CodePlanningUtil;
import de.upb.crc901.mlplan.core.MLUtil;
import de.upb.crc901.mlplan.core.SolutionEvaluator;
import jaicore.basic.SetUtil;
import jaicore.basic.SetUtil.Pair;
import jaicore.logging.LoggerUtil;
import jaicore.logic.fol.structure.ConstantParam;
import jaicore.logic.fol.structure.Literal;
import jaicore.logic.fol.structure.Monom;
import jaicore.planning.graphgenerators.task.tfd.TFDNode;
import jaicore.planning.model.ceoc.CEOCAction;
import jaicore.planning.model.core.Action;
import jaicore.planning.model.task.ceocstn.CEOCSTNUtil;
import jaicore.planning.model.task.stn.MethodInstance;
import jaicore.search.algorithms.parallel.parallelexploration.distributed.interfaces.SerializableGraphGenerator;
import jaicore.search.algorithms.parallel.parallelexploration.distributed.interfaces.SerializableNodeEvaluator;
import jaicore.search.algorithms.standard.bestfirst.BestFirst;
import jaicore.search.algorithms.standard.core.ICancelableNodeEvaluator;
import jaicore.search.algorithms.standard.core.IGraphDependentNodeEvaluator;
import jaicore.search.algorithms.standard.core.ISolutionReportingNodeEvaluator;
import jaicore.search.algorithms.standard.core.NodeAnnotationEvent;
import jaicore.search.algorithms.standard.core.SolutionAnnotationEvent;
import jaicore.search.algorithms.standard.core.SolutionEventBus;
import jaicore.search.algorithms.standard.core.SolutionFoundEvent;
import jaicore.search.algorithms.standard.rdfs.RandomizedDepthFirstSearch;
import jaicore.search.structure.core.GraphGenerator;
import jaicore.search.structure.core.Node;
import jaicore.search.structure.graphgenerator.GoalTester;
import jaicore.search.structure.graphgenerator.SingleRootGenerator;
import jaicore.search.structure.graphgenerator.SuccessorGenerator;
import weka.classifiers.Classifier;
import weka.core.Instances;

@SuppressWarnings("serial")
public abstract class RandomCompletionEvaluator<V extends Comparable<V>> implements IGraphDependentNodeEvaluator<TFDNode, String, V>, DataDependentNodeEvaluator<TFDNode, V>,
		SerializableNodeEvaluator<TFDNode, V>, ISolutionReportingNodeEvaluator<TFDNode, V>, ICancelableNodeEvaluator {

	private final static Logger logger = LoggerFactory.getLogger(RandomCompletionEvaluator.class);
	private Map<List<TFDNode>, List<TFDNode>> completions = new ConcurrentHashMap<>();
	private Map<List<TFDNode>, List<CEOCAction>> knownSolutions = new HashMap<>();
	private Set<List<TFDNode>> postedSolutions = new HashSet<>();
	private Set<List<CEOCAction>> unsuccessfulPlans = Collections.synchronizedSet(new HashSet<>());
	private Map<List<CEOCAction>, V> scoresOfSolutionPaths = new ConcurrentHashMap<>();
	private Map<List<CEOCAction>, Integer> pipelineScoreTimes = new ConcurrentHashMap<>();
	protected Map<Node<TFDNode, ?>, V> fValues = new ConcurrentHashMap<>();
	protected Map<String, Integer> ppFails = new ConcurrentHashMap<>();
	protected Map<String, Integer> plFails = new ConcurrentHashMap<>();
	protected Map<String, Integer> plSuccesses = new ConcurrentHashMap<>();

	private SerializableGraphGenerator<TFDNode, String> generator;
	private long timestampOfFirstEvaluation;
	private final Random random;
	protected final int samples;
	protected final SolutionEvaluator evaluator;
	private transient SolutionEventBus<TFDNode> eventBus;
	private boolean dataSet = false;

	// private final Map<String,AttributeSelection> cachedFilters = new HashMap<>();
	private int maxSolutionsPerTechnique = -1;
	private final Map<String, Map<String, Integer>> solutionsPerTechnique = new HashMap<>();

	public RandomCompletionEvaluator(Random random, int samples, SolutionEvaluator evaluator) {
		super();
		if (random == null)
			throw new IllegalArgumentException("Random source must not be null!");
		if (samples <= 0)
			throw new IllegalArgumentException("Sample size must be greater than 0!");
		if (evaluator == null)
			throw new IllegalArgumentException("Solution Evaluator must not be null!");
		this.random = random;
		this.samples = samples;
		this.evaluator = evaluator;
		
		/* check whether assertions are on */
		boolean assertOn = false;
		assert assertOn = true; 
		if (assertOn) {
			System.out.println("--------------------------------------------------------");
			System.out.println("Attention: assertions are activated.");
			System.out.println("This causes significant performance loss using RandomCompleter.");
			System.out.println("If you are not in debugging mode, we strongly suggest to deactive assertions.");
			System.out.println("--------------------------------------------------------");
		}
	}

	protected V computeEvaluationPriorToCompletion(Node<TFDNode, ?> n, List<TFDNode> path, List<CEOCAction> plan, List<String> currentProgram) throws Throwable {
		return null;
	}

	protected abstract V convertErrorRateToNodeEvaluation(Integer errorRate);

	protected abstract double getExpectedUpperBoundForRelativeDistanceToOptimalSolution(Node<TFDNode, ?> n, List<TFDNode> path, List<CEOCAction> partialPlan,
			List<String> currentProgram);

	public V f(Node<TFDNode, ?> n) throws Throwable {
		if (timestampOfFirstEvaluation == 0)
			timestampOfFirstEvaluation = System.currentTimeMillis();
		logger.info("Received request for f-value of node {}", n);

		if (!fValues.containsKey(n)) {

			/* if we already have a value for this path, do not continue */
			if (generator == null)
				throw new IllegalStateException("Cannot compute f-values before the generator is set!");

			/* compute path and partial plan belonging to the node */
			List<TFDNode> path = n.externalPath();
			TFDNode currentNode = path.get(path.size() - 1);
			Literal currentTask = currentNode.getRemainingTasks().isEmpty() ? null : currentNode.getRemainingTasks().get(0);
			List<CEOCAction> partialPlan = CEOCSTNUtil.extractPlanFromSolutionPath(path);
			List<String> currentProgram = Arrays.asList(MLUtil.getJavaCodeFromPlan(partialPlan).split("\n"));

			/* annotate node with estimated relative distance to optimal solution */
			if (eventBus == null)
				eventBus = new SolutionEventBus<>();
			eventBus.post(new NodeAnnotationEvent<>(n.getPoint(), "EUBRD2OS", getExpectedUpperBoundForRelativeDistanceToOptimalSolution(n, path, partialPlan, currentProgram)));
			
			List<Long> pathNodeIds = path.stream().map(node -> node.getID()).collect(Collectors.toList());

			if (!n.getPoint().isGoal()) {
				logger.info("This is an unknown node; computing score for path to node: {}", pathNodeIds);
				assert !scoresOfSolutionPaths.containsKey(partialPlan) : "A non-goal path is stored in the list of scores of solution paths!";
				
				V evaluationPriorToCompletion = computeEvaluationPriorToCompletion(n, path, partialPlan, currentProgram);
				if (evaluationPriorToCompletion != null) {
					fValues.put(n, evaluationPriorToCompletion);
					return evaluationPriorToCompletion;
				}
				
				/* if there was no relevant change in comparison to parent, apply parent's f */
				if (path.size() > 1 && !MLUtil.didLastActionAffectPipeline(path)) {
					V score = fValues.get(n.getParent());
					fValues.put(n, score);
					logger.info("Pipeline has not changed in node {}, adopting value of {} of parent.", n.getPoint().getID(), score);
					return score;
				}
				
//				TFDNode lastNode = path.get(path.size() - 1);
//				System.out.println(resolvedProblem.getPropertyName() + ". Resolved by " + (lastNode.getAppliedAction() != null ? lastNode.getAppliedAction().getEncoding() : lastNode.getAppliedMethodInstance().getEncoding()));

				/* check if we have an f-value for exactly this node */
				if (!completions.containsKey(path)) {
					
					logger.info("No completion is explicitly known for path {}.", pathNodeIds);

					/* determine preprocessor and classifier of pipeline */
					Optional<String> preprocessorLine = currentProgram.stream().filter(line -> line.contains("new") && line.contains("attributeSelection")).findAny();
					String preprocessorName = preprocessorLine.isPresent() ? CodePlanningUtil.getPreprocessorEvaluatorFromPipelineGenerationCode(currentProgram) : "";
					String classifierName = CodePlanningUtil.getClassifierFromPipelineGenerationCode(currentProgram);
					String plName = preprocessorName + "&" + classifierName;
					
					/* ignore if preprocessing fails even with oneR */
					String reference = preprocessorName + "&OneR";
//					if (plFails.containsKey(reference)) {
//						logger.info("Cancel {}, because even OneR does not finish within time using this preprocessor!", plName);
//						return null;
//					}
					
					/* if this specific pipeline has failed before, ignore it also now */
//					if (plFails.containsKey(plName)) {
//						logger.info("Ignoreing pipeline which has failed before.");
//						return null;
//					}

					/* if the space under this solution is overly searched, reject */
					if (maxSolutionsPerTechnique >= 0 && solutionsPerTechnique.containsKey(preprocessorName)
							&& solutionsPerTechnique.get(preprocessorName).containsKey(classifierName)
							&& solutionsPerTechnique.get(preprocessorName).get(classifierName) >= maxSolutionsPerTechnique) {
						logger.warn("Returning null to prevent oversearch");
						return null;// new IllegalArgumentException("This node is in an oversearched region");
					}

					/* determine whether we have a solution path (found by the oracle) that goes over this node */
					/* only if we have no path to a solution over this node, we compute a new one */
					List<TFDNode> pathWhoseCompletionSubsumesCurrentPath = getSubsumingKnownPathCompletion(path);
					assert pathWhoseCompletionSubsumesCurrentPath == null || pathWhoseCompletionSubsumesCurrentPath.subList(0, path.size()).equals(path) : "The path completion " + pathWhoseCompletionSubsumesCurrentPath.stream().map(node -> node.getID()).collect(Collectors.toList()) + " does NOT subsume path " + pathNodeIds + ".\n\tStep-Wise Comparison (current above, (not) subsuming below): " + ContiguousSet.create(Range.closed(0, Math.max(path.size(), pathWhoseCompletionSubsumesCurrentPath.size())), DiscreteDomain.integers()).asList().stream().map(i -> "\n\t" + i + "\n\t\t" + (i < path.size() ? path.get(i).toString() : "") + "\n\t\t" + (i < pathWhoseCompletionSubsumesCurrentPath.size() ? pathWhoseCompletionSubsumesCurrentPath.get(i).toString() : "")).collect(Collectors.toList());
					logger.info("Result of a look-up for a path that would subsume {}: {}.", pathNodeIds, pathWhoseCompletionSubsumesCurrentPath != null ? pathWhoseCompletionSubsumesCurrentPath.stream().map(node -> node.getID()).collect(Collectors.toList()) : null);

					boolean interrupted = false;
					if (pathWhoseCompletionSubsumesCurrentPath == null) {
						V best = null;
						List<TFDNode> bestCompletion = null;
						int i = 0;
						int j = 0;
						final int maxSamples = samples * 2;
						for (; i < samples; i++) {
							
							if (Thread.interrupted()) {
								interrupted = true;
								break;
							}

							/* create randomized dfs searcher */
							BestFirst<TFDNode, String> completer = new RandomizedDepthFirstSearch<>(new GraphGenerator<TFDNode, String>() {
								public SingleRootGenerator<TFDNode> getRootGenerator() {
									return () -> n.getPoint();
								}

								public SuccessorGenerator<TFDNode, String> getSuccessorGenerator() {
									return generator.getSuccessorGenerator();
								}

								public GoalTester<TFDNode> getGoalTester() {
									return generator.getGoalTester();
								}

								@Override
								public boolean isSelfContained() {
									// TODO Auto-generated method stub
									return false;
								}

								@Override
								public void setNodeNumbering(boolean nodenumbering) {
									// TODO Auto-generated method stub
									
								}
							}, random);

							/* now complete the current path by the dfs-solution */
//							new SimpleGraphVisualizationWindow<>(completer.getEventBus()).getPanel().setTooltipGenerator(new TFDTooltipGenerator<>());
							List<TFDNode> completedPath = new ArrayList<>(n.externalPath());
							logger.info("Starting search for next solution ...");
							List<TFDNode> pathCompletion = completer.nextSolution();
							if (pathCompletion == null) {
								logger.warn("No completion was found for currently remaining tasks {}. Nodes expanded in search: {}", currentNode.getRemainingTasks(), completer.getExpandedCounter());
								return null;
							}
							logger.info("Found solution {}", pathCompletion.stream().map(node -> node.getID()).collect(Collectors.toList()));
							pathCompletion.remove(0);
							completedPath.addAll(pathCompletion);

							/* now evaluate this solution */
							j++;
							try {
								V val = getFValueOfSolutionPath(completedPath);
								if (val != null) {
									if (best == null || val.compareTo(best) < 0) {
										best = val;
										bestCompletion = completedPath;
									}
								}
							} catch (InterruptedException e) {
								interrupted = true;
								break;
							} catch (Throwable ex) {
								if (j ==maxSamples) {
									logger.warn("Too many retry attempts, giving up.");
									throw ex;
								}
								else
									LoggerUtil.logException("Could not evaluate solution candidate ... retry another completion.", ex, logger);
							}
						}
						
						/* add number of samples to node  */
						n.setAnnotation("fRPSamples", i);
						
						if (bestCompletion == null) {
							countPLFail(plName);
							logger.info("Did not find any successful completion for classifier {}. Interrupted: {}", classifierName, interrupted);
							if (interrupted)
								throw new InterruptedException();
							logger.warn("Did not find any completion");
							return null;
						}
						
						/* we have been interrupted, but there are intermediate results. We accept these */
						if (interrupted) {
							logger.info("Estimate {} is only based on {} instead of {} samples, because we received an interrupt.", best, i, samples);
						}
						
						countPLSuccess(plName);
						assert isSolutionPath(bestCompletion) : "Identified a completion that is no solution path!";
						assert scoresOfSolutionPaths.containsKey(CEOCSTNUtil.extractPlanFromSolutionPath(bestCompletion)) : "Solution was detected but its score was not saved";
						completions.put(path, bestCompletion);
					} else {
						assert isSolutionPath(completions.get(pathWhoseCompletionSubsumesCurrentPath)) : "Identified a subsuming completion "
								+ pathWhoseCompletionSubsumesCurrentPath.stream().map(l -> l.toString() + "\n").collect(Collectors.toList()) + " that is no solution path!";
						completions.put(path, completions.get(pathWhoseCompletionSubsumesCurrentPath));
					}
				}
				fValues.put(n, getFValueOfSolutionPath(completions.get(path)));
			}

			/* the node is a goal node */
			else {

				/* record that we found a new solution for this technique */
				String preprocessorName = CodePlanningUtil.getPreprocessorEvaluatorFromPipelineGenerationCode(currentProgram);
				String classifierName = CodePlanningUtil.getClassifierFromPipelineGenerationCode(currentProgram);
				if (!solutionsPerTechnique.containsKey(preprocessorName))
					solutionsPerTechnique.put(preprocessorName, new HashMap<>());
				if (!solutionsPerTechnique.get(preprocessorName).containsKey(classifierName))
					solutionsPerTechnique.get(preprocessorName).put(classifierName, 0);
				int currentlyExploredVariants = solutionsPerTechnique.get(preprocessorName).get(classifierName);
				solutionsPerTechnique.get(preprocessorName).put(classifierName, currentlyExploredVariants + 1);

				V score = getFValueOfSolutionPath(path);
				if (score == null) {
					logger.warn("No score was computed");
					return null;
				}
				fValues.put(n, score);
				if (!postedSolutions.contains(path)) {
					logger.error("Found a goal node whose solution has not been posted before!");
					// for (List<CEOCAction> plan : knownSolutions.values()) {
					// int counter = 0;
					// for (List<TFDNode> path : knownSolutions.keySet()) {
					// if (knownSolutions.get(path).equals(plan))
					// counter ++;
					// }
					// if (counter > 1) {
					// System.err.println("Plan " + plan + " has " + counter + " paths");
					// for (List<TFDNode> path : knownSolutions.keySet()) {
					// if (knownSolutions.get(path).equals(plan))
					// System.err.println("\t" + path);
					// }
					// }
					logger.error("Partial plan is {}", partialPlan);
					logger.error("Is an unsuccessful plan? {}. F-Value: {}", unsuccessfulPlans.contains(partialPlan), scoresOfSolutionPaths.get(path));
					System.exit(0);
					// }
				}
			}
		}
		V f = fValues.get(n);
		logger.info("Returning f-value: {}", f);
		return f;
	}
	
	private void countPLFail(String plName) {
		int fails = 0;
		if (!plFails.containsKey(plName)) {
			fails = 1;
		} else
			fails = plFails.get(plName) + 1;
		plFails.put(plName, fails);
	}
	
	private void countPLSuccess(String plName) {
		int successes = 0;
		if (!plSuccesses.containsKey(plName)) {
			successes = 1;
		} else
			successes = plSuccesses.get(plName) + 1;
		plSuccesses.put(plName, successes);
	}

	private V getFValueOfSolutionPath(List<TFDNode> path) throws Throwable {
		assert isSolutionPath(path) : "Can only compute f-values for completed plans, but it is invoked with a plan that does not yield a goal node!";
		List<CEOCAction> plan = CEOCSTNUtil.extractPlanFromSolutionPath(path);
		logger.info("Compute f-value for path {} and its plan {}", path.stream().map(n -> n.getID()).collect(Collectors.toList()), plan.stream().map(a -> a.getEncoding()).collect(Collectors.toList()));
		assert checkPathPlanBijection(path, plan);
		boolean knownPath = scoresOfSolutionPaths.containsKey(plan);
		if (!knownPath) {
			if (!dataSet)
				throw new IllegalStateException("Cannot compute f-values if data have not been set!");

			if (unsuccessfulPlans.contains(plan)) {
				logger.info("Associated plan was evaluated unsuccessfully in a previous run; returning NULL: {}", plan);
				return null;
			}
			logger.info("Associated plan is new. Compute f-value for complete plan {}", plan);

			long start = System.currentTimeMillis();
			V val = null;
			try {
				val = convertErrorRateToNodeEvaluation(evaluator.getSolutionScore(MLUtil.extractGeneratedClassifierFromPlan(plan)));
			} catch (Throwable e) {
				unsuccessfulPlans.add(plan);
				throw e;
			}
			
			long duration = System.currentTimeMillis() - start;
			logger.info("Result: {}, Size: {}", val, scoresOfSolutionPaths.size());
			if (val == null) {
				unsuccessfulPlans.add(plan);
				return null;
			}

			scoresOfSolutionPaths.put(plan, val);
			pipelineScoreTimes.put(plan, (int) duration);
			postSolution(path);
		} else {
			logger.info("Associated plan is known. Reading score from cache.");
			if (logger.isTraceEnabled()) {
				for (List<CEOCAction> existingPlan : scoresOfSolutionPaths.keySet()) {
					if (existingPlan.equals(plan)) {
						logger.trace("The following plans appear equal:\n\t{}\n\t{}", existingPlan, plan);
					}
				}
			}
			if (!postedSolutions.contains(path))
				throw new IllegalStateException("Reading cached score of a plan whose path has not been posted as a solution! Are there several paths to a plan?");
		}
		V score = scoresOfSolutionPaths.get(plan);
		logger.info("Determined value {} for pipeline {}.", score, plan.stream().map(a -> a.getEncoding()).collect(Collectors.toList()));
		return score;
	}

	private boolean checkPathPlanBijection(List<TFDNode> pPath, List<CEOCAction> pPlan) {
		synchronized (knownSolutions) {
			knownSolutions.put(pPath, pPlan);
			for (List<CEOCAction> plan : knownSolutions.values()) {
				List<List<TFDNode>> paths = new ArrayList<>();
				for (List<TFDNode> path : knownSolutions.keySet()) {
					if (knownSolutions.get(path).equals(plan))
						paths.add(path);
				}
				if (paths.size() > 1) {
					System.err.println("There are " + paths.size() + " paths to plan " + plan);
					Set<List<CEOCAction>> plans = new HashSet<>();
					for (List<TFDNode> path : paths) {
						List<CEOCAction> planForThisPath = CEOCSTNUtil.extractPlanFromSolutionPath(path);
						System.err.println("\tPath: " + path);
						System.err.println("\tPlan: " + planForThisPath);
						plans.add(planForThisPath);
					}
					if (plans.size() != paths.size()) {
						System.err.println(
								"There are only " + plans.size() + " different plans according to equals, but all the " + paths.size() + " plans are (should be) different.");
					}
					return false;
				}
			}
			return true;
		}
	}

	private boolean isSolutionPath(List<TFDNode> path) {
		return path.get(path.size() - 1).isGoal();
	}

	private List<TFDNode> getSubsumingKnownPathCompletion(List<TFDNode> path) throws InterruptedException {
		List<Long> pathIds = path.stream().map(n -> n.getID()).collect(Collectors.toList());
		for (List<TFDNode> partialPath : completions.keySet()) {
			List<Long> partialPathIds = partialPath.stream().map(n -> n.getID()).collect(Collectors.toList());
			List<TFDNode> compl = completions.get(partialPath);
			logger.debug("Checking whether {} subsumes {}. The known completion is {}", partialPathIds, pathIds, compl.stream().map(n -> n.getID()).collect(Collectors.toList()));
			if (compl.size() < path.size()) {
				logger.debug("Ignoring this partial path, because its completion is shorter than the path we already have.");
				continue;
			}
			if (path.equals(compl)) {
				logger.debug("Return true, because the paths are even equal.");
				return compl;
			}

			Map<ConstantParam, ConstantParam> map = new HashMap<>();
			boolean allUnifiable = true;
			for (int i = 0; i < path.size(); i++) {
				TFDNode current = path.get(i);
				TFDNode partner = compl.get(i);
				
				/* check whether the chosen method or operation is the same */
				final Action a1 = current.getAppliedAction();
				final Action a2 = partner.getAppliedAction();
				if ((a1 == null) != (a2 == null)) {
					allUnifiable = false;
					logger.trace("Not unifiable because one node applies an action and the other not (either it applies nothing or a method instance).");
					break;
				}
				if (a1 != null && !a1.getOperation().equals(a2.getOperation())) {
					allUnifiable = false;
					logger.trace("Not unifiable because operations {} and {} of a1 and a2 respectively deviate", a1.getOperation(), a2.getOperation());
					break;
				}
				if (a1 == null) {
					final MethodInstance mi1 = current.getAppliedMethodInstance();
					final MethodInstance mi2 = partner.getAppliedMethodInstance();
					
					/* the nodes just don't do anything (should be the root) */
					if (mi1 == null && mi2 == null) {
						continue;
					}
					
					if ((mi1 == null) != (mi2 == null)) {
						allUnifiable = false;
						logger.trace("Not unifiable because one node applies a method instance and the other not (either an action or nothing)");
						break;
					}
					if (!mi1.getMethod().equals(mi2.getMethod())) {
						allUnifiable = false;
						logger.trace("Not unifiable because methods {} and {} of m1 and m2 respectively deviate", mi1.getMethod(), mi2.getMethod());
						break;
					}
				}

				/* compute substitutions of new vars */
				Collection<ConstantParam> varsInCurrent = new HashSet<>(current.getState().getConstantParams());
				for (Literal l : current.getRemainingTasks())
					varsInCurrent.addAll(l.getConstantParams());
				Collection<ConstantParam> varsInPartner = new HashSet<>(partner.getState().getConstantParams());
				for (Literal l : partner.getRemainingTasks())
					varsInPartner.addAll(l.getConstantParams());
				Collection<ConstantParam> unboundVars = SetUtil.difference(varsInCurrent, map.keySet());
				Collection<ConstantParam> possibleTargets = SetUtil.difference(varsInPartner, map.values());
				for (ConstantParam p : new ArrayList<>(unboundVars)) {
					if (possibleTargets.contains(p)) {
						map.put(p, p);
						unboundVars.remove(p);
						possibleTargets.remove(p);
					}
				}

				/* if the relation between vars in the nodes is completely known, we can easily decide whether they are unifiable */
				if (unboundVars.isEmpty()) {
					if (getRenamedState(current.getState(), map).equals(partner.getState())
							&& getRenamedRemainingList(current.getRemainingTasks(), map).equals(partner.getRemainingTasks()))
						continue;
					else {
						allUnifiable = false;
						break;
					}
				}

				/* otherwise, we must check possible mappings between the still unbound vars */
				boolean unified = false;
				Collection<Map<ConstantParam, ConstantParam>> possibleMappingCompletions = SetUtil.allMappings(unboundVars, possibleTargets, true, true, true);
				for (Map<ConstantParam, ConstantParam> mappingCompletion : possibleMappingCompletions) {

					/* first check whether the state is equal */
					Monom copy = getRenamedState(current.getState(), mappingCompletion);
					if (!copy.equals(partner.getState()))
						continue;

					/* if this is the case, check whether the remaining tasks are equal */
					List<Literal> copyOfTasks = getRenamedRemainingList(current.getRemainingTasks(), mappingCompletion);
					if (!copyOfTasks.equals(partner.getRemainingTasks()))
						continue;

					/* now we know that this node can be unified. We add the respective map and quit the current node pair */
					map.putAll(mappingCompletion);
					unified = true;
					break;

				}
				if (!unified) {
					allUnifiable = false;
					break;
				}
			}

			/* if all nodes were unifiable, return this path */
			if (allUnifiable) {
				logger.debug("Returning true, because this path is unifiable with the given one.");
				return partialPath;
			}
		}
		return null;

	}

	protected void postSolution(List<TFDNode> solution) {
		if (postedSolutions.contains(solution))
			throw new IllegalArgumentException("Solution " + solution.toString() + " already posted!");
		postedSolutions.add(solution);
		List<CEOCAction> plan = CEOCSTNUtil.extractPlanFromSolutionPath(solution);
		try {
			Classifier c = MLUtil.extractGeneratedClassifierFromPlan(plan);
	
			/* now post the solution to the event bus */
			int numberOfComputedFValues = scoresOfSolutionPaths.size();
	
			/* post solution and then the annotations */
			if (eventBus == null)
				eventBus = new SolutionEventBus<>();
			eventBus.post(new SolutionFoundEvent<>(solution, scoresOfSolutionPaths.get(plan)));
			eventBus.post(new SolutionAnnotationEvent<>(solution, "fTime", pipelineScoreTimes.get(plan)));
			eventBus.post(new SolutionAnnotationEvent<>(solution, "timeToSolution", (int) (System.currentTimeMillis() - timestampOfFirstEvaluation)));
			eventBus.post(new SolutionAnnotationEvent<>(solution, "nodesExpandedToSolution", numberOfComputedFValues));
			eventBus.post(new SolutionAnnotationEvent<>(solution, "isTunedSolution", solution.stream().filter(a -> a.getAppliedAction() != null)
					.filter(a -> a.getAppliedAction().getOperation().getName().contains("setOptions")).findAny().isPresent()));
			eventBus.post(new SolutionAnnotationEvent<>(solution, "classifier", c));
		}
		catch (Throwable e) {
			List<Pair<String, Object>> explanations = new ArrayList<>();
			if (logger.isDebugEnabled()) {
				StringBuilder sb = new StringBuilder();
				solution.forEach(n -> sb.append(n.toString() + "\n"));
				explanations.add(new Pair<>("The path that has been tried to convert is as follows:", sb.toString()));
			}
			LoggerUtil.logException("Cannot post solution, because no valid MLPipeline object could be derived from it.", e, logger, explanations);
		}
	}

	private Monom getRenamedState(Monom state, Map<ConstantParam, ConstantParam> map) {
		Monom copy = new Monom(state, map);
		return copy;
	}

	private List<Literal> getRenamedRemainingList(List<Literal> remainingList, Map<ConstantParam, ConstantParam> map) {
		List<Literal> copyOfTasks = new ArrayList<>();
		for (Literal l : remainingList) {
			copyOfTasks.add(new Literal(l, map));
		}
		return copyOfTasks;
	}

	@Override
	public void setData(Instances data) {
		this.evaluator.setData(data);
		this.dataSet = true;
	}

	@Override
	public void setGenerator(GraphGenerator<TFDNode, String> generator) {
		this.generator = (SerializableGraphGenerator<TFDNode, String>)generator;
	}

	@Override
	public SolutionEventBus<TFDNode> getSolutionEventBus() {
		if (this.eventBus == null) {
			this.eventBus = new SolutionEventBus<>();
		}
		return this.eventBus;
	}

	@Override
	public void cancel() {
		logger.info("Receive cancel signal.");
		this.evaluator.cancel();
	}

	public int getMaxSolutionsPerTechnique() {
		return maxSolutionsPerTechnique;
	}

	public void setMaxSolutionsPerTechnique(int maxSolutionsPerTechnique) {
		this.maxSolutionsPerTechnique = maxSolutionsPerTechnique;
	}
}
