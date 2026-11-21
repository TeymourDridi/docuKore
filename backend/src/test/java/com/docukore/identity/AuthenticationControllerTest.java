package com.docukore.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationControllerTest
{
    @Test
    void publicSetupStatusDistinguishesHostedFromInitializedLocalRegistration()
    {
        var setup = mock(LocalSetupService.class);
        var accounts = mock(AccountRepository.class);
        when(setup.available()).thenReturn(false);

        var local = new AuthenticationController(setup, accounts,
                new DeploymentProperties(DeploymentProperties.Mode.LOCAL));
        var hosted = new AuthenticationController(setup, accounts,
                new DeploymentProperties(DeploymentProperties.Mode.HOSTED));

        assertThat(local.setupStatus().available()).isFalse();
        assertThat(local.setupStatus().deploymentMode()).isEqualTo(DeploymentProperties.Mode.LOCAL);
        assertThat(hosted.setupStatus().available()).isFalse();
        assertThat(hosted.setupStatus().deploymentMode()).isEqualTo(DeploymentProperties.Mode.HOSTED);
    }
}
