/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.password.ldap;

import io.trino.spi.security.AccessDeniedException;
import org.testng.annotations.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;

public class TestLdapGroupProvider
{
    private static final LdapConfig DEFAULT_LDAP_CONFIG = new LdapConfig()
            .setBindDistingushedName("server")
            .setBindPassword("server-pass")
            .setGroupAuthorizationSearchPattern(TestLdapClient.PATTERN_PREFIX + "${USER}")
            .setUserBaseDistinguishedName(TestLdapClient.USER_BASE_DN)
            .setGroupBaseDistinguishedName(TestLdapClient.GROUP_BASE_DN);

    @Test
    public void testGetGroups()
    {
        TestLdapClient client = new TestLdapClient();
        client.addCredentials("server", "server-pass");
        client.addDistinguishedNameForUser("alice", "alice");

        LdapConfig ldapConfig = DEFAULT_LDAP_CONFIG;
        LdapCommon ldapCommon = new LdapCommon(client, ldapConfig);

        LdapGroupProvider ldapGroupProvider = new LdapGroupProvider(client, ldapConfig, ldapCommon);

        assertEquals(ldapGroupProvider.getGroups("alice"), Collections.emptySet());
        client.addGroupMember("alice");
        assertEquals(ldapGroupProvider.getGroups("alice"), Collections.emptySet());
        ldapGroupProvider.invalidateCache();
        assertEquals(ldapGroupProvider.getGroups("alice"), Collections.singleton(TestLdapClient.DEFAULT_GROUP_NAME));
    }

    @Test
    public void testAllowUserNotExistIfDefault()
    {
        TestLdapClient client = new TestLdapClient();
        client.addCredentials("server", "server-pass");
        client.addDistinguishedNameForUser("alice", "alice");

        LdapConfig ldapConfig = DEFAULT_LDAP_CONFIG;
        LdapCommon ldapCommon = new LdapCommon(client, ldapConfig);

        LdapGroupProvider ldapGroupProvider = new LdapGroupProvider(client, ldapConfig, ldapCommon);

        assertThatThrownBy(() -> ldapGroupProvider.getGroups("bob")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    public void testAllowUserNotExistIfTrue()
    {
        TestLdapClient client = new TestLdapClient();
        client.addCredentials("server", "server-pass");
        client.addDistinguishedNameForUser("alice", "alice");

        LdapConfig ldapConfig = DEFAULT_LDAP_CONFIG
                .setAllowUserNotExist(true);
        LdapCommon ldapCommon = new LdapCommon(client, ldapConfig);

        LdapGroupProvider ldapGroupProvider = new LdapGroupProvider(client, ldapConfig, ldapCommon);

        assertEquals(ldapGroupProvider.getGroups("bob"), Collections.emptySet());
    }
}
