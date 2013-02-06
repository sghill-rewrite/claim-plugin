package hudson.plugins.claim;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Saveable;
import hudson.tasks.junit.*;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import hudson.util.EditDistance;
import org.kohsuke.stapler.DataBoundConstructor;

public class ClaimTestDataPublisher extends TestDataPublisher {

    private final boolean onlyCopyClaimOnSimilarError;

	@DataBoundConstructor
	public ClaimTestDataPublisher(boolean onlyCopySimilarClaims) {
        this.onlyCopyClaimOnSimilarError = onlyCopySimilarClaims;
    }


    public boolean isOnlyCopyClaimOnSimilarError() {
        return onlyCopyClaimOnSimilarError;
    }

    @Override
	public Data getTestData(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener, TestResult testResult) {
		Data data = new Data(build);

		for (SuiteResult suite: testResult.getSuites()) {
			for (CaseResult result: suite.getCases()) {
				CaseResult previous = result.getPreviousResult();
				if (previous != null) {
					ClaimTestAction previousAction = previous.getTestAction(ClaimTestAction.class);
					if (previousAction != null && previousAction.isClaimed()) {
						if (result.getFailCount() > 0 && previousAction.isSticky()) {
                            if (!onlyCopyClaimOnSimilarError || isSimilar(previous, result)) {
                                ClaimTestAction action = new ClaimTestAction(data, result.getId());
                                previousAction.copyTo(action);
                                data.addClaim(result.getId(), action);
                            }
						}
						if (result.getFailCount() == 0) {
							new ClaimTestAction(data, result.getId()).unclaim();
						}
					}
				}
			}
		}

		return data;
		
	}

    public boolean isSimilar(CaseResult a, CaseResult b) {
        int similarity = Integer.getInteger(getClass().getName() + ".similarity", 7);

        String errorA = a.getErrorDetails();
        String errorB = b.getErrorDetails();

        int margin = Math.max(errorA.length(), errorB.length()) / similarity;

        return EditDistance.editDistance(errorA, errorB) < margin;
    }
	
	public static class Data extends TestResultAction.Data implements Saveable {

		private Map<String,ClaimTestAction> claims = new HashMap<String,ClaimTestAction>();

		private final AbstractBuild<?,?> build;

		public Data(AbstractBuild<?,?> build) {
			this.build = build;
		}

		@Override
		public List<TestAction> getTestAction(TestObject testObject) {
			String id = testObject.getId();
			ClaimTestAction result = claims.get(id);

			// In Hudson 1.347 or so, IDs changed, and a junit/ prefix was added.
			// Attempt to fix this backward-incompatibility
			if (result == null && id.startsWith("junit")) {
				result = claims.get(id.substring(5));
			}
			
			if (result != null) {
				return Collections.<TestAction>singletonList(result);
			}
			
			if (testObject instanceof CaseResult) {
				CaseResult cr = (CaseResult) testObject;
				if (!cr.isPassed() && !cr.isSkipped()) {
					return Collections.<TestAction>singletonList(new ClaimTestAction(this, id));
				}
			}
			
			return Collections.emptyList();
		}

		public void save() throws IOException {
			build.save();
		}

		public void addClaim(String testObjectId,
				ClaimTestAction claim) {
			claims.put(testObjectId, claim);
		}

		public AbstractBuild<?, ?> getBuild() {
			return build;
		}
	}
	
	@Extension
	public static class DescriptorImpl extends Descriptor<TestDataPublisher> {
		
		@Override
		public String getDisplayName() {
			return "Allow claiming of failed tests";
		}
	}


}