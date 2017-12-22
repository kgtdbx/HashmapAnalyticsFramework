package com.hashmap.haf.providers

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = Array("security.client"), havingValue = "ldap")
class LdapAuthenticationProvider extends CustomAuthenticationProvider{

}
