package com.revengemission.sso.oauth2.server.controller;

import com.revengemission.sso.oauth2.server.config.CachesEnum;
import com.revengemission.sso.oauth2.server.domain.OauthClient;
import com.revengemission.sso.oauth2.server.domain.ScopeDefinition;
import com.revengemission.sso.oauth2.server.domain.UserInfo;
import com.revengemission.sso.oauth2.server.service.OauthClientService;
import com.revengemission.sso.oauth2.server.service.ScopeDefinitionService;
import com.revengemission.sso.oauth2.server.token.AuthorizationCodeTokenGranter;
import com.revengemission.sso.oauth2.server.token.PasswordTokenGranter;
import com.revengemission.sso.oauth2.server.token.RefreshTokenGranter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.KeyPair;
import java.util.*;

@Controller
@RequestMapping("/oauth")
public class OAuth2Controller {
    private Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    OauthClientService oauthClientService;

    @Autowired
    ScopeDefinitionService scopeDefinitionService;


    CacheManager cacheManager;

    private AuthenticationManager authenticationManager;

    private KeyPair keyPair;
    private String issuerUri;

    PasswordTokenGranter passwordTokenGranter;
    RefreshTokenGranter refreshTokenGranter;
    AuthorizationCodeTokenGranter authorizationCodeTokenGranter;

    public OAuth2Controller(AuthenticationManager authenticationManager, KeyPair keyPair, CacheManager cacheManager, @Value("${oauth2.issuer-uri:http://localhost:10380}") String issuerUri,StringRedisTemplate stringRedisTemplate) {
        this.authenticationManager = authenticationManager;
        this.keyPair = keyPair;
        this.cacheManager = cacheManager;
        this.issuerUri = issuerUri;
        passwordTokenGranter = new PasswordTokenGranter(authenticationManager, keyPair, issuerUri);
        refreshTokenGranter = new RefreshTokenGranter(authenticationManager, keyPair, issuerUri);
        authorizationCodeTokenGranter = new AuthorizationCodeTokenGranter(authenticationManager, cacheManager, keyPair, issuerUri, stringRedisTemplate);
    }

    @PostMapping(value={"/token"})
    public ResponseEntity<Map<String, Object>> token(HttpServletRequest request) {
        String client_id = request.getParameter("client_id");
        String client_secret = request.getParameter("client_secret");
        String grant_type = request.getParameter("grant_type");
        String scope = request.getParameter("scope");
        String redirect_uri = request.getParameter("redirect_uri");
        String refresh_token = request.getParameter("refresh_token");
        String code = request.getParameter("code");
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        log.info("client_id = {},redirect_uri= {}, code = {}",client_id,redirect_uri , code);
        Map<String, Object> result = new HashMap<>(16);
        OauthClient client = oauthClientService.findByClientId(client_id);
        HttpHeaders headers = new HttpHeaders();

        if (client == null) {
            result.put("status", 0);
            result.put("code", "invalid_client");
            result.put("message", "invalid_client");
            return new ResponseEntity<>(
                result, headers, HttpStatus.OK);
        }

        Map<String, String> parameters = new HashMap<>();
        parameters.put("client_id", client_id);
        parameters.put("client_secret", client_secret);
        parameters.put("grant_type", grant_type);
        parameters.put("scope", scope);
        parameters.put("redirect_uri", redirect_uri);
        parameters.put("refresh_token", refresh_token);
        parameters.put("code", code);
        parameters.put("username", username);
        parameters.put("password", password);
        if (StringUtils.equalsIgnoreCase(grant_type, "password")) {
            result = passwordTokenGranter.grant(client, "password", parameters);
        } else if (StringUtils.equalsIgnoreCase(grant_type, "authorization_code")) {
            if (StringUtils.isEmpty(redirect_uri) || !StringUtils.equalsIgnoreCase(client.getWebServerRedirectUri(), redirect_uri)) {
                result.put("status", 0);
                result.put("code", "invalid_redirect_uri");
                result.put("message", "invalid_redirect_uri");
                return new ResponseEntity<>(
                    result, headers, HttpStatus.OK);
            }
            result = authorizationCodeTokenGranter.grant(client, "authorization_code", parameters);
        } else if (StringUtils.equalsIgnoreCase(grant_type, "refresh_token")) {
            result = refreshTokenGranter.grant(client, grant_type, parameters);
        } else {
            result.put("status", 0);
            result.put("message", "不支持的grant类型");
        }
        return new ResponseEntity<>(
            result, headers, HttpStatus.OK);
    }



    @GetMapping("/authorize")
    public String authorize(ModelMap model,
                            Authentication authentication,
                            @RequestHeader(name = "referer", required = false) String referer,
                            @RequestParam(value = "client_id") String client_id,
                            @RequestParam(value = "response_type") String response_type,
                            @RequestParam(value = "state", required = false) String state,
                            @RequestParam(value = "scope", required = false) String scopes,
                            @RequestParam(value = "redirect_uri") String redirect_uri,
                            HttpServletResponse response) {
        OauthClient client = oauthClientService.findByClientId(client_id);

        if (client == null || !StringUtils.equalsIgnoreCase(client.getWebServerRedirectUri(), redirect_uri)) {
            if (redirect_uri.indexOf("?") > 0) {
                return "redirect:" + redirect_uri + "&error=invalid_client";
            } else {
                return "redirect:" + redirect_uri + "?error=invalid_client";
            }
        }
        String uuid = UUID.randomUUID().toString().replace("-", "");
        if ("1".equals(client.getAutoApprove())) {
            if(authentication == null){

                UserInfo userInfo = new UserInfo(uuid,client_id,client_id,new ArrayList<>());
                authentication = new UsernamePasswordAuthenticationToken(userInfo,client_id);
            }
            cacheManager.getCache(CachesEnum.Oauth2AuthorizationCodeCache.name()).put(uuid, authentication);

            if (client.getWebServerRedirectUri().indexOf("?") > 0) {
                return "redirect:" + client.getWebServerRedirectUri() + "&code=" + uuid + "&state=" + state;
            } else {
                return "redirect:" + client.getWebServerRedirectUri() + "?code=" + uuid + "&state=" + state;
            }
        } else {
            if(authentication == null){

                UserInfo userInfo = new UserInfo(uuid,client_id,client_id,new ArrayList<>());
                authentication = new UsernamePasswordAuthenticationToken(userInfo,client_id);
            }
            cacheManager.getCache(CachesEnum.Oauth2AuthorizationCodeCache.name()).put(uuid, authentication);

            model.put("client_id", client_id);
            model.put("applicationName", client.getApplicationName());
            model.put("from", referer);
            model.put("state", state);
            model.put("redirect_uri", redirect_uri);
            Map<String, String> scopeMap = new LinkedHashMap<>();
            for (String scope : scopes.split(",")) {
                ScopeDefinition scopeDefinition = scopeDefinitionService.findByScope(scope);
                if (scopeDefinition != null) {
                    scopeMap.put("scope." + scope, scopeDefinition.getDefinition());
                } else {
                    scopeMap.put("scope." + scope, scope);
                }
            }
            model.put("scopeMap", scopeMap);
            try {
                String url = "http://bziyun.com/h5/miAuth?redirect_uri=" + redirect_uri + "&code=" + uuid + "&state=" + state;
                log.info("url = {}",url);
                response.sendRedirect(url);
            }catch (Exception e){

            }
            return "accessConfirmation";
        }
    }

    @PostMapping("/authorize")
    public String postAccessToken(ModelMap model,
                                  Authentication authentication,
                                  @RequestParam(name = "referer", required = false) String referer,
                                  @RequestParam(value = "client_id") String client_id,
                                  @RequestParam(value = "response_type", required = false) String response_type,
                                  @RequestParam(value = "state", required = false) String state,
                                  @RequestParam(value = "scope", required = false) String scope,
                                  @RequestParam(value = "user_oauth_approval", required = false, defaultValue = "false") boolean userOauthApproval,
                                  @RequestParam(value = "redirect_uri") String redirect_uri) {
        OauthClient client = oauthClientService.findByClientId(client_id);
        model.put("client_id", client_id);
        model.put("applicationName", client.getApplicationName());
        model.put("from", referer);

        if (userOauthApproval) {
            String uuid = UUID.randomUUID().toString().replace("-", "");
            cacheManager.getCache(CachesEnum.Oauth2AuthorizationCodeCache.name()).put(uuid, authentication);
            if (client.getWebServerRedirectUri().indexOf("?") > 0) {
                return "redirect:" + client.getWebServerRedirectUri() + "&code=" + uuid + "&state=" + state;
            } else {
                return "redirect:" + client.getWebServerRedirectUri() + "?code=" + uuid + "&state=" + state;
            }
        } else {
            if (redirect_uri.indexOf("?") > 0) {
                return "redirect:" + redirect_uri + "&state=" + state + "&error=not_approval";
            } else {
                return "redirect:" + redirect_uri + "&state=" + state + "?error=not_approval";
            }
        }
    }

    @ResponseBody
    @PostMapping("/check_token")
    public Map<String, Object> checkToken(@RequestParam(value = "access_token") String access_token) {
        Map<String, Object> result = new HashMap<>(16);
        try {
          Object object =Jwts.parserBuilder().setSigningKey(keyPair.getPublic()).build().parseClaimsJws(access_token).getBody();
          log.info("object = {}",object);
            result.put("status", 1);
        } catch (Exception e) {
            result.put("status", 0);
            result.put("message", "access_token 无效");
            if (log.isErrorEnabled()) {
                log.error("验证token异常", e);
            }
        }
        return result;
    }

    @ResponseBody
    @PostMapping("/getAccount")
    public String getAccount(@RequestParam(value = "access_token") String access_token){
        try {
            Claims claims =Jwts.parserBuilder().setSigningKey(keyPair.getPublic()).build().parseClaimsJws(access_token).getBody();
            return claims.get("accountOpenCode",String.class);
        } catch (Exception e) {

        }
        return "";
    }


}
