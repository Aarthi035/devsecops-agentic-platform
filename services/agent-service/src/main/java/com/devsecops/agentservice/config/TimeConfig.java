package com.devsecops.agentservice.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock the agent reads time from.
 *
 * <p>Injected rather than called statically, because one piece of behaviour
 * depends on the passage of time and cannot be tested without controlling it:
 * an approval expires. With a fixed clock a test can ask for a proposal whose
 * time to live is already past and assert what happens, instead of sleeping for
 * a second and hoping the scheduler is kind.
 *
 * <p>UTC, and {@link java.time.Clock#systemUTC()} rather than the system default
 * zone: every timestamp the agent writes - audit entries, proposal deadlines -
 * goes to a log or a JSON body that another system reads, and those should not
 * change meaning with the operator's laptop settings.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
