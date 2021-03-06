package jaicore.planning.model.task.stn;

import jaicore.logic.fol.structure.CNFFormula;
import jaicore.logic.fol.structure.Monom;
import jaicore.planning.model.core.Action;
import jaicore.planning.model.core.Operation;
import jaicore.planning.model.task.IHTNPlanningProblem;

@SuppressWarnings("serial")
public class STNPlanningProblem<O extends Operation, M extends Method, A extends Action> implements IHTNPlanningProblem<O, M, A> {

	private final STNPlanningDomain<O, M> domain;
	private final CNFFormula knowledge;
	private final Monom init;
	private final TaskNetwork network;
	private final boolean sortNetworkBasedOnNumberPrefixes = true;

	public STNPlanningProblem(STNPlanningDomain<O,M> domain, CNFFormula knowledge, Monom init, TaskNetwork network) {
		super();
		this.domain = domain;
		this.knowledge = knowledge;
		this.init = init;
		this.network = network;
	}

	public STNPlanningDomain<O,M> getDomain() {
		return domain;
	}

	public CNFFormula getKnowledge() {
		return knowledge;
	}

	public Monom getInit() {
		return init;
	}

	public TaskNetwork getNetwork() {
		return network;
	}

	public boolean isSortNetworkBasedOnNumberPrefixes() {
		return sortNetworkBasedOnNumberPrefixes;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((domain == null) ? 0 : domain.hashCode());
		result = prime * result + ((init == null) ? 0 : init.hashCode());
		result = prime * result + ((knowledge == null) ? 0 : knowledge.hashCode());
		result = prime * result + ((network == null) ? 0 : network.hashCode());
		result = prime * result + (sortNetworkBasedOnNumberPrefixes ? 1231 : 1237);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		STNPlanningProblem other = (STNPlanningProblem) obj;
		if (domain == null) {
			if (other.domain != null)
				return false;
		} else if (!domain.equals(other.domain))
			return false;
		if (init == null) {
			if (other.init != null)
				return false;
		} else if (!init.equals(other.init))
			return false;
		if (knowledge == null) {
			if (other.knowledge != null)
				return false;
		} else if (!knowledge.equals(other.knowledge))
			return false;
		if (network == null) {
			if (other.network != null)
				return false;
		} else if (!network.equals(other.network))
			return false;
		if (sortNetworkBasedOnNumberPrefixes != other.sortNetworkBasedOnNumberPrefixes)
			return false;
		return true;
	}
}
