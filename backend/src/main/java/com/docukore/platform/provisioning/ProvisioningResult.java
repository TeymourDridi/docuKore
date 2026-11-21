package com.docukore.platform.provisioning;

import java.util.UUID;

/**
 * Original cabinet identity and provisioning outcome, never privileged schema details.
 *
 * @param cabinetId the cabinet identifier
 * @param status    the provisioning status
 */
public record ProvisioningResult(UUID cabinetId, ProvisioningStatus status)
{
}
