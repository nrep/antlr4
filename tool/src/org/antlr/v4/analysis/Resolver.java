package org.antlr.v4.analysis;

import org.antlr.v4.automata.DFAState;
import org.antlr.v4.automata.NFA;
import org.antlr.v4.misc.Utils;
import org.stringtemplate.v4.misc.MultiMap;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Code "module" that knows how to resolve LL(*) nondeterminisms. */
public class Resolver {
	StackLimitedNFAToDFAConverter converter;

	PredicateResolver semResolver;

	public Resolver(StackLimitedNFAToDFAConverter converter) {
		this.converter = converter;
		semResolver = new PredicateResolver(converter);
	}
	
	/** Walk each NFA configuration in this DFA state looking for a conflict
	 *  where (s|i|ctx) and (s|j|ctx) exist, indicating that state s with
	 *  conflicting ctx predicts alts i and j.  Return an Integer set
	 *  of the alternative numbers that conflict.  Two contexts conflict if
	 *  they are equal or one is a stack suffix of the other or one is
	 *  the empty context.  The conflict is a true ambiguity. No amount
	 *  of further looking in grammar will resolve issue (only preds help).
	 *
	 *  Use a hash table to record the lists of configs for each state
	 *  as they are encountered.  We need only consider states for which
	 *  there is more than one configuration.  The configurations' predicted
	 *  alt must be different or must have different contexts to avoid a
	 *  conflict.
	 */
	public Set<Integer> getAmbiguousAlts(DFAState d) {
		//System.out.println("getNondetAlts for DFA state "+stateNumber);
		 Set<Integer> ambiguousAlts = new HashSet<Integer>();

		// If only 1 NFA conf then no way it can be nondeterministic;
		// save the overhead.  There are many o-a->o NFA transitions
		// and so we save a hash map and iterator creation for each
		// state.
		int numConfigs = d.nfaConfigs.size();
		if ( numConfigs<=1 ) return null;

		// First get a list of configurations for each state.
		// Most of the time, each state will have one associated configuration.
		MultiMap<Integer, NFAConfig> stateToConfigListMap =
			new MultiMap<Integer, NFAConfig>();
		for (NFAConfig c : d.nfaConfigs) {
			stateToConfigListMap.map(Utils.integer(c.state.stateNumber), c);
		}

		// potential conflicts are states with > 1 configuration and diff alts
//		boolean thisStateHasPotentialProblem = false;
		boolean deterministic = true;
		for (List<NFAConfig> configsForState : stateToConfigListMap.values()) {
			if ( configsForState.size()>1 ) {
				int predictedAlt = Resolver.getUniqueAlt(configsForState, false);
				if ( predictedAlt > 0 ) {
					// remove NFA state's configurations from
					// further checking; no issues with it
					// (can't remove as it's concurrent modification; set to null)
					stateToConfigListMap.put(configsForState.get(0).state.stateNumber, null);
				}
				else {
					//thisStateHasPotentialProblem = true;
					deterministic = false;
				}
			}
		}

		// a fast check for potential issues; most states have none
//		if ( !thisStateHasPotentialProblem ) return null;

		if ( deterministic ) {
			d.isAcceptState = true;
			return null;
		}

		// we have a potential problem, so now go through config lists again
		// looking for different alts (only states with potential issues
		// are left in the states set).  Now we will check context.
		// For example, the list of configs for NFA state 3 in some DFA
		// state might be:
		//   [3|2|[28 18 $], 3|1|[28 $], 3|1, 3|2]
		// I want to create a map from context to alts looking for overlap:
		//   [28 18 $] -> 2
		//   [28 $] -> 1
		//   [$] -> 1,2
		// Indeed a conflict exists as same state 3, same context [$], predicts
		// alts 1 and 2.
		// walk each state with potential conflicting configurations
		for (List<NFAConfig> configsForState : stateToConfigListMap.values()) {
			// compare each configuration pair s, t to ensure:
			// s.ctx different than t.ctx if s.alt != t.alt
			int numConfigsForState = 0;
			if ( configsForState!=null ) numConfigsForState = configsForState.size();
			for (int i = 0; i < numConfigsForState; i++) {
				NFAConfig s = (NFAConfig) configsForState.get(i);
				for (int j = i+1; j < numConfigsForState; j++) {
					NFAConfig t = (NFAConfig)configsForState.get(j);
					// conflicts means s.ctx==t.ctx or s.ctx is a stack
					// suffix of t.ctx or vice versa (if alts differ).
					// Also a conflict if s.ctx or t.ctx is empty
					boolean altConflict = s.alt != t.alt;
					boolean ctxConflict = false;
					if ( converter instanceof StackLimitedNFAToDFAConverter) {
						ctxConflict = s.context.equals(t.context);
					}
					else {
						ctxConflict = s.context.conflictsWith(t.context);
					}
					if ( altConflict && ctxConflict ) {
						ambiguousAlts.add(s.alt);
						ambiguousAlts.add(t.alt);
					}
				}
			}
		}

		if ( ambiguousAlts.size()==0 ) return null;
		return ambiguousAlts;
	}

	public void resolveAmbiguities(DFAState d) {
		if ( StackLimitedNFAToDFAConverter.debug ) {
			System.out.println("resolveNonDeterminisms "+d.toString());
		}
		Set<Integer> ambiguousAlts = getAmbiguousAlts(d);
		if ( StackLimitedNFAToDFAConverter.debug && ambiguousAlts!=null ) {
			System.out.println("ambig alts="+ambiguousAlts);
		}

		// if no problems return
		if ( ambiguousAlts==null ) return;

		converter.ambiguousStates.add(d);

		// ATTEMPT TO RESOLVE WITH SEMANTIC PREDICATES
		boolean resolved =
			semResolver.tryToResolveWithSemanticPredicates(d, ambiguousAlts);
		if ( resolved ) {
			if ( StackLimitedNFAToDFAConverter.debug ) {
				System.out.println("resolved DFA state "+d.stateNumber+" with pred");
			}
			d.resolvedWithPredicates = true;
			converter.resolvedWithSemanticPredicates.add(d);
			return;
		}

		// RESOLVE SYNTACTIC CONFLICT BY REMOVING ALL BUT ONE ALT
		resolveByPickingMinAlt(d, ambiguousAlts);
	}


	public void resolveDanglingState(DFAState d) {
		if ( d.resolvedWithPredicates || d.getNumberOfTransitions()>0 ) return;
		
		System.err.println("dangling DFA state "+d+" after reach / closures");
		converter.danglingStates.add(d);
		// turn off all configurations except for those associated with
		// min alt number; somebody has to win else some input will not
		// predict any alt.
		int minAlt = resolveByPickingMinAlt(d, null);
		// force it to be an accept state
		d.isAcceptState = true;
		// might be adding new accept state for alt, but that's ok
		converter.dfa.defineAcceptState(minAlt, d);
	}

	/** Turn off all configurations associated with the
	 *  set of incoming alts except the min alt number.
	 *  There may be many alts among the configurations but only turn off
	 *  the ones with problems (other than the min alt of course).
	 *
	 *  If alts is null then turn off all configs 'cept those
	 *  associated with the minimum alt.
	 *
	 *  Return the min alt found.
	 */
	int resolveByPickingMinAlt(DFAState d, Set<Integer> alts) {
		int min = Integer.MAX_VALUE;
		if ( alts !=null ) {
			min = getMinAlt(alts);
		}
		else {
			min = d.getMinAlt();
		}

		turnOffOtherAlts(d, min, alts);

		return min;
	}

	/** turn off all states associated with alts other than the good one
	 *  (as long as they are one of the ones in alts)
	 */
	void turnOffOtherAlts(DFAState d, int min, Set<Integer> alts) {
		int numConfigs = d.nfaConfigs.size();
		for (int i = 0; i < numConfigs; i++) {
			NFAConfig configuration = d.nfaConfigs.get(i);
			if ( configuration.alt!=min ) {
				if ( alts==null ||
					 alts.contains(configuration.alt) )
				{
					configuration.resolved = true;
				}
			}
		}
	}

	public static int getMinAlt(Set<Integer> alts) {
		int min = Integer.MAX_VALUE;
		for (Integer altI : alts) {
			int alt = altI.intValue();
			if ( alt < min ) min = alt;
		}
		return min;
	}

	public static int getUniqueAlt(Collection<NFAConfig> nfaConfigs,
								   boolean ignoreResolved)
	{
		int alt = NFA.INVALID_ALT_NUMBER;
		for (NFAConfig c : nfaConfigs) {
			if ( !ignoreResolved && c.resolved ) continue;
			if ( alt==NFA.INVALID_ALT_NUMBER ) {
				alt = c.alt; // found first alt
			}
			else if ( c.alt!=alt ) {
				return NFA.INVALID_ALT_NUMBER;
			}
		}
		return alt;
	}
}
