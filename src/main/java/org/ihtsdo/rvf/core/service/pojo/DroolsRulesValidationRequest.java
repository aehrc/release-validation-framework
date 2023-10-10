package org.ihtsdo.rvf.core.service.pojo;

import java.util.List;

public class DroolsRulesValidationRequest {
	
	private String effectiveTime;
	
	private boolean releaseAsAnEdition;
	
	private String includedModules;
	
	private List<String> droolsRulesGroups;

	public String getEffectiveTime() {
		return effectiveTime;
	}

	public void setEffectiveTime(String effectiveTime) {
		this.effectiveTime = effectiveTime;
	}

	public boolean isReleaseAsAnEdition() {
		return releaseAsAnEdition;
	}

	public void setReleaseAsAnEdition(boolean releaseAsAnEdition) {
		this.releaseAsAnEdition = releaseAsAnEdition;
	}

	public String getIncludedModules() {
		return includedModules;
	}

	public void setIncludedModules(String includedModules) {
		this.includedModules = includedModules;
	}

	public List<String> getDroolsRulesGroups() {
		return droolsRulesGroups;
	}

	public void setDroolsRulesGroups(List<String> droolsRulesGroups) {
		this.droolsRulesGroups = droolsRulesGroups;
	}
	
}
