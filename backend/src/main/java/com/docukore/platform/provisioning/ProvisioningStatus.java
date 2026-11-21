package com.docukore.platform.provisioning;

/** Public outcomes; detailed internal phases remain backend metadata. */
public enum ProvisioningStatus
{
    /** The original cabinet was finalized, or an already ACTIVE result was observed. */
    ACTIVE,
    /** Another worker holds the original cabinet's lock; retry the same registration. */
    IN_PROGRESS
}
