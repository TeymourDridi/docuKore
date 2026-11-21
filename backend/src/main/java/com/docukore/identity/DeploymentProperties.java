package com.docukore.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("docukore.deployment")
public record DeploymentProperties(Mode mode)
{

    /** Creates a DeploymentProperties instance, defaulting to HOSTED if null. */
    public DeploymentProperties
    {
        if (mode == null)
        {
            mode = Mode.HOSTED;
        }
    }

    /** Returns true if running in local deployment mode. */
    public boolean local()
    {
        return mode == Mode.LOCAL;
    }

    /** Deployment mode configuration. */
    public enum Mode
    {
        /** Local development/production mode. */
        LOCAL,
        /** Hosted SaaS mode. */
        HOSTED
    }
}