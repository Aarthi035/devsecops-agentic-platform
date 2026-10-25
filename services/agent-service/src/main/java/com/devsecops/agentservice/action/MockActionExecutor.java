package com.devsecops.agentservice.action;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@link ActionExecutor} that does nothing at all.
 *
 * <p>Selected by {@code agent.tools.mode=mock}, the same switch that swaps the
 * diagnostic tools for fixtures. One switch governing both means "mock" is a
 * single, checkable property of a deployment: an agent in mock mode can neither
 * read a real cluster nor change one, so a demo or a test can be run against it
 * without wondering which half is live.
 *
 * <p>It still records what it was asked to do, and that record is what lets a
 * test assert that an approved action reached the executor with the right
 * parameters - which is the interesting behaviour, and the part a no-op would
 * otherwise hide. {@link #executions()} is that record.
 */
@Component
@ConditionalOnProperty(name = "agent.tools.mode", havingValue = "mock")
public class MockActionExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(MockActionExecutor.class);

    private final List<ActionProposal> executions = new CopyOnWriteArrayList<>();

    @Override
    public String mode() {
        return "mock";
    }

    @Override
    public ActionOutcome execute(ActionProposal proposal) {
        executions.add(proposal);
        log.info("Mock executor accepted '{}' (action {}); no cluster was contacted.",
                proposal.describe(), proposal.id());
        return ActionOutcome.success(proposal.describe(),
                "Executed in mock mode: the action was approved and recorded, but no cluster was contacted.",
                "mock");
    }

    /** Every proposal this executor was asked to run, oldest first. */
    public List<ActionProposal> executions() {
        return List.copyOf(executions);
    }

    /** Forgets the record; for a test that asserts on one case at a time. */
    public void reset() {
        executions.clear();
    }
}
