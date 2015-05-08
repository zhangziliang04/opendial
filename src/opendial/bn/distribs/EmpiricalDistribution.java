// =================================================================                                                                   
// Copyright (C) 2011-2015 Pierre Lison (plison@ifi.uio.no)

// Permission is hereby granted, free of charge, to any person 
// obtaining a copy of this software and associated documentation 
// files (the "Software"), to deal in the Software without restriction, 
// including without limitation the rights to use, copy, modify, merge, 
// publish, distribute, sublicense, and/or sell copies of the Software, 
// and to permit persons to whom the Software is furnished to do so, 
// subject to the following conditions:

// The above copyright notice and this permission notice shall be 
// included in all copies or substantial portions of the Software.

// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
// IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY 
// CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, 
// TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE 
// SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
// =================================================================                                                                   

package opendial.bn.distribs;

import java.util.logging.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import opendial.bn.distribs.densityfunctions.KernelDensityFunction;
import opendial.bn.values.ArrayVal;
import opendial.bn.values.DoubleVal;
import opendial.bn.values.Value;
import opendial.datastructs.Assignment;
import opendial.utils.InferenceUtils;

/**
 * Distribution defined "empirically" in terms of a set of samples on a collection of
 * random variables. This distribution can then be explicitly converted into a table
 * or a continuous distribution (depending on the variable type).
 *
 * @author Pierre Lison (plison@ifi.uio.no)
 *
 */
public class EmpiricalDistribution implements MultivariateDistribution {

	// logger
	public final static Logger log = Logger.getLogger("OpenDial");

	// list of samples for the empirical distribution
	protected List<Assignment> samples;

	// random sampler
	Random sampler;

	// cache for the discrete and continuous distributions
	MultivariateTable discreteCache;
	ContinuousDistribution continuousCache;

	// the names of the random variables
	Set<String> variables;

	// ===================================
	// CONSTRUCTION METHODS
	// ===================================

	/**
	 * Constructs an empirical distribution with an empty set of samples
	 */
	public EmpiricalDistribution() {
		this.samples = new ArrayList<Assignment>();
		this.variables = new HashSet<String>();
		sampler = new Random();
	}

	/**
	 * Constructs a new empirical distribution with a set of samples samples
	 * 
	 * @param samples the samples to add
	 */
	public EmpiricalDistribution(Collection<? extends Assignment> samples) {
		this();
		for (Assignment a : samples) {
			addSample(a);
		}
	}

	/**
	 * Adds a new sample to the distribution
	 * 
	 * @param sample the sample to add
	 */
	public void addSample(Assignment sample) {
		samples.add(sample);
		discreteCache = null;
		continuousCache = null;
		variables.addAll(sample.getVariables());
	}

	/**
	 * Removes a particular variable from the sampled assignments
	 * 
	 * @param varId the id of the variable to remove
	 */
	public void removeVariable(String varId) {
		variables.remove(varId);
		discreteCache = null;
		continuousCache = null;
		for (Assignment s : samples) {
			s.removePair(varId);
		}
	}

	// ===================================
	// GETTERS
	// ===================================

	/**
	 * Samples from the distribution. In this case, simply selects one arbitrary
	 * sample out of the set defining the distribution
	 * 
	 * @return the selected sample
	 */
	@Override
	public Assignment sample() {

		if (!samples.isEmpty()) {
			int selection = sampler.nextInt(samples.size());
			Assignment selected = samples.get(selection);
			return selected;
		}
		else {
			log.warning("distribution has no samples");
			return new Assignment();
		}
	}

	/**
	 * Returns a sample that is compatible with the provided evidence.
	 * 
	 * @param evidence the evidence.
	 * @return the compatible sample
	 * @throws RuntimeException if no samples are consistent with the evidence.
	 */
	public Assignment getCompatibleSample(Assignment evidence)
			throws RuntimeException {
		for (int i = 0; i < 10; i++) {
			Assignment sampled = sample();
			if (sampled.consistentWith(evidence)) {
				return sampled;
			}
		}
		Collections.shuffle(samples);
		for (Assignment sample : samples) {
			if (sample.consistentWith(evidence)) {
				return sample;
			}
		}
		throw new RuntimeException("no sample consistent with " + evidence + " for "
				+ toString());
	}

	/**
	 * Returns the head variables for the distribution.
	 */
	@Override
	public Set<String> getVariables() {
		return new HashSet<String>(variables);
	}

	/**
	 * Returns the collection of samples.
	 * 
	 * @return the collection of samples
	 */
	public Collection<Assignment> getSamples() {
		return samples;
	}

	/**
	 * Returns the number of samples.
	 * 
	 * @return the number of samples.
	 */
	public int size() {
		return samples.size();
	}

	/**
	 * Returns the possible values for the variables of the distribution.
	 * 
	 * @return the possible values for the variables
	 */
	@Override
	public Set<Assignment> getValues() {
		Set<Assignment> possible = new HashSet<Assignment>();
		for (Assignment sample : samples) {
			possible.add(sample);
		}
		return possible;
	}

	/**
	 * Returns the probability of a particular assignment
	 */
	@Override
	public double getProb(Assignment head) {
		return toDiscrete().getProb(head);
	}

	/**
	 * Returns the value that occurs most often in the set of samples
	 * 
	 */
	@Override
	public Assignment getBest() {
		return toDiscrete().getBest();
	}

	// ===================================
	// CONVERSION METHODS
	// ===================================

	/**
	 * Returns a discrete representation of the empirical distribution.
	 */
	@Override
	public MultivariateTable toDiscrete() {
		if (discreteCache == null) {
			discreteCache = createMultivariateTable(variables);
		}
		return discreteCache;

	}

	/**
	 * Returns a continuous representation of the distribution (if there is no
	 * conditional variables in the distribution).
	 * 
	 * @return the corresponding continuous distribution.
	 * @throws RuntimeException if the distribution content is discrete.
	 */
	public ContinuousDistribution toContinuous() throws RuntimeException {
		if (continuousCache == null) {
			if (variables.size() != 1) {
				throw new RuntimeException(
						"cannot convert distribution to continuous for P("
								+ variables + ")");
			}
			String headVar = variables.iterator().next();
			continuousCache = createContinuousDistribution(headVar);
		}
		return continuousCache;
	}

	/**
	 * Returns an independent probability distribution on a single random variable
	 * based on the samples. This distribution may be a categorical table or a
	 * continuous distribution.
	 * 
	 * @return the probability distribution resulting from the marginalisation.
	 */
	@Override
	public IndependentProbDistribution getMarginal(String var) {
		if (sample().getTrimmed(var).containContinuousValues()
				&& samples.stream().distinct().limit(5).count() == 5) {
			return this.createContinuousDistribution(var);
		}
		else {
			return createUnivariateTable(var);
		}
	}

	/**
	 * Returns a distribution P(var|condvars) based on the samples. If the
	 * conditional variables are empty, returns an independent probability
	 * distribution.
	 * 
	 * @param var the head variable
	 * @param condVars the conditional variables
	 * @return the resulting probability distribution
	 * @throws RuntimeException if the distribution could not be generated.
	 */
	public ProbDistribution getMarginal(String var, Set<String> condVars)
			throws RuntimeException {

		if (condVars.isEmpty()) {
			return getMarginal(var);
		}
		else {
			return this.createConditionalTable(var, condVars);
		}
	}

	/**
	 * Creates a categorical table with the define subset of variables (or a single
	 * value distribution if only a single value is found).
	 * 
	 * @param headVars the subset of variables to include in the table
	 * @return the resulting table
	 */
	protected IndependentProbDistribution createUnivariateTable(String headVar) {

		Map<Value, Double> probs = new HashMap<Value, Double>();

		double incr = 1.0 / samples.size();
		for (Assignment sample : samples) {
			Value val = sample.getValue(headVar);
			probs.put(val, probs.getOrDefault(val, 0.0) + incr);
		}
		if (probs.size() == 1) {
			return new SingleValueDistribution(headVar, probs.keySet().iterator()
					.next());
		}

		CategoricalTable table = new CategoricalTable(headVar, probs);

		if (!table.isWellFormed()) {
			log.warning("table created by discretising samples is not well-formed");
		}
		return table;
	}

	/**
	 * Creates a categorical table with the define subset of variables
	 * 
	 * @param headVars the subset of variables to include in the table
	 * @return the resulting table
	 */
	protected MultivariateTable createMultivariateTable(Collection<String> headVars) {

		Map<Assignment, Double> probs = new HashMap<Assignment, Double>();

		double incr = 1.0 / samples.size();
		for (Assignment sample : samples) {
			Assignment trimmed = sample.getTrimmed(headVars);
			probs.put(trimmed, probs.getOrDefault(trimmed, 0.0) + incr);
		}

		MultivariateTable table = new MultivariateTable(probs);

		if (!table.isWellFormed()) {
			log.warning("table created by discretising samples is not well-formed");
		}
		return table;
	}

	/**
	 * Creates a continuous distribution for the provided variable
	 * 
	 * @param variable the variable
	 * @return the resulting continuous distribution
	 */
	protected ContinuousDistribution createContinuousDistribution(String variable) {

		List<double[]> values = new ArrayList<double[]>();
		for (Assignment a : samples) {
			Value v = a.getValue(variable);
			if (v instanceof ArrayVal) {
				values.add(((ArrayVal) v).getArray());
			}
			else if (v instanceof DoubleVal) {
				values.add(new double[] { ((DoubleVal) v).getDouble() });
			}
		}
		return new ContinuousDistribution(variable,
				new KernelDensityFunction(values));
	}

	/**
	 * Creates a conditional categorical table derived from the samples. This method
	 * should be called in the presence of conditional variables.
	 * 
	 * @param headVar the name of the head variable
	 * @param condVars the conditional variables.
	 * @return the conditional categorical table
	 */
	@SuppressWarnings("rawtypes")
	public ConditionalDistribution createConditionalTable(String headVar,
			Collection<String> condVars) {

		Map<Assignment, Map<Value, Double>> temp =
				new HashMap<Assignment, Map<Value, Double>>();
		for (Assignment sample : samples) {
			Assignment condition = sample.getTrimmed(condVars);
			Value val = sample.getValue(headVar);
			if (!temp.containsKey(condition)) {
				Map<Value, Double> counts = new HashMap<Value, Double>();
				counts.put(val, 1.0);
				temp.put(condition, counts);
			}
			else {
				Map<Value, Double> counts = temp.get(condition);
				counts.put(val, counts.getOrDefault(val, 0.0) + 1);
			}
		}

		ConditionalTable table = new ConditionalTable(headVar);
		for (Assignment condition : temp.keySet()) {
			Map<Value, Double> probs = InferenceUtils.normalise(temp.get(condition));
			CategoricalTable catTable = new CategoricalTable(headVar, probs);
			table.addRows(condition, catTable);
		}

		if (table.isDeterministic()) {
			return table.getDeterministic();
		}
		return table;
	}

	// ===================================
	// UTILITY METHODS
	// ===================================

	/**
	 * Prunes all samples that contain a value whose relative frequency is below the
	 * threshold specified as argument. DoubleVal and ArrayVal are ignored.
	 * 
	 * @param threshold the frequency threshold
	 */
	@Override
	public boolean pruneValues(double threshold) {

		Map<String, Map<Value, Integer>> frequencies =
				new HashMap<String, Map<Value, Integer>>();

		for (Assignment sample : samples) {
			for (String var : sample.getVariables()) {
				Value val = sample.getValue(var);
				if (!frequencies.containsKey(var)) {
					frequencies.put(var, new HashMap<Value, Integer>());
				}
				Map<Value, Integer> valFreq = frequencies.get(var);
				if (!valFreq.containsKey(val)) {
					valFreq.put(val, 1);
				}
				else {
					valFreq.put(val, valFreq.get(val) + 1);
				}
			}
		}

		boolean changed = false;
		int minNumber = (int) (samples.size() * threshold);
		for (int i = 0; i < samples.size(); i++) {
			Assignment sample = samples.get(i);
			for (String var : sample.getVariables()) {
				if (frequencies.get(var).get(sample.getValue(var)) < minNumber) {
					samples.remove(i);
					changed = true;
					continue;
				}
			}
		}
		discreteCache = null;
		continuousCache = null;
		return changed;
	}

	/**
	 * Replace a variable label by a new one
	 * 
	 * @param oldId the old variable label
	 * @param newId the new variable label
	 */
	@Override
	public void modifyVariableId(String oldId, String newId) {

		if (variables.contains(oldId)) {
			variables.remove(oldId);
			variables.add(newId);
		}

		for (Assignment a : samples) {
			if (a.containsVar(oldId)) {
				Value v = a.removePair(oldId);
				a.addPair(newId, v);
			}
		}

		if (discreteCache != null) {
			discreteCache.modifyVariableId(oldId, newId);
		}
		if (continuousCache != null) {
			continuousCache.modifyVariableId(oldId, newId);
		}
	}

	/**
	 * Returns a copy of the distribution
	 * 
	 * @return the copy
	 */
	@Override
	public EmpiricalDistribution copy() {
		EmpiricalDistribution copy = new EmpiricalDistribution(samples);
		return copy;
	}

	/**
	 * Returns a pretty print representation of the distribution: here, tries to
	 * convert it to a discrete distribution, and displays its content.
	 * 
	 * @return the pretty print
	 */
	@Override
	public String toString() {

		if (isContinuous()) {
			try {
				return toContinuous().toString();
			}
			catch (RuntimeException e) {
				log.fine("could not convert distribution to a continuous format: "
						+ e);
			}
		}
		return toDiscrete().toString();
	}

	private boolean isContinuous() {

		for (String var : getVariables()) {
			Assignment a = samples.get(0);
			if (a.containsVar(var) && a.containContinuousValues()) {
				if (getVariables().size() == 1) {
					return true;
				}
				else {
					return false;
				}
			}
		}
		return false;
	}

}
