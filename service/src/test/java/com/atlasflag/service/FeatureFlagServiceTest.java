package com.atlasflag.service;

import com.atlasflag.domain.FeatureFlag;
import com.atlasflag.dto.BulkEvaluationRequest;
import com.atlasflag.dto.FeatureFlagDTO;
import com.atlasflag.dto.FlagEvaluationRequest;
import com.atlasflag.dto.FlagEvaluationResponse;
import com.atlasflag.repository.FeatureFlagRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock FeatureFlagRepository flagRepository;
    @Mock AuditService auditService;
    @Mock WebhookService webhookService;
    @Mock EvaluationAnalyticsService analyticsService;
    @Mock FlagChangePublisher flagChangePublisher;

    FeatureFlagService service;

    @BeforeEach
    void setUp() {
        service = new FeatureFlagService(
            flagRepository, auditService, webhookService,
            new ObjectMapper(), analyticsService, flagChangePublisher
        );
    }

    // ── evaluateFlag ─────────────────────────────────────────────────────────

    @Test
    void evaluateFlag_flagNotFound_returnsFalseAndNoAnalytics() {
        when(flagRepository.findByFlagKeyAndEnvironment("missing", "PRODUCTION"))
            .thenReturn(Optional.empty());

        FlagEvaluationResponse res = service.evaluateFlag(req("missing", "PRODUCTION"));

        assertThat(res.getEnabled()).isFalse();
        assertThat(res.getReason()).isEqualTo("FLAG_NOT_FOUND");
        assertThat(res.getValue()).isNull();
        verifyNoInteractions(analyticsService);
    }

    @Test
    void evaluateFlag_flagDisabled_returnsDefaultValueFalse() {
        FeatureFlag flag = flag("my-flag", "DEV", false);
        flag.setDefaultValue(false);
        mockFlag(flag);

        FlagEvaluationResponse res = service.evaluateFlag(req("my-flag", "DEV"));

        assertThat(res.getEnabled()).isFalse();
        assertThat(res.getReason()).isEqualTo("FLAG_DISABLED");
        verify(analyticsService).record("my-flag", "DEV", false);
    }

    @Test
    void evaluateFlag_flagDisabledWithDefaultValueTrue_returnsTrue() {
        FeatureFlag flag = flag("my-flag", "DEV", false);
        flag.setDefaultValue(true);
        mockFlag(flag);

        FlagEvaluationResponse res = service.evaluateFlag(req("my-flag", "DEV"));

        assertThat(res.getEnabled()).isTrue();
        assertThat(res.getReason()).isEqualTo("FLAG_DISABLED");
        verify(analyticsService).record("my-flag", "DEV", true);
    }

    @Test
    void evaluateFlag_flagEnabled_noRollout_returnsEnabled() {
        mockFlag(flag("feature", "PRODUCTION", true));

        FlagEvaluationResponse res = service.evaluateFlag(req("feature", "PRODUCTION"));

        assertThat(res.getEnabled()).isTrue();
        assertThat(res.getReason()).isEqualTo("FLAG_ENABLED");
        verify(analyticsService).record("feature", "PRODUCTION", true);
    }

    @Test
    void evaluateFlag_stringFlag_enabled_returnsValue() {
        FeatureFlag flag = flag("checkout-color", "PRODUCTION", true);
        flag.setFlagType("STRING");
        flag.setStringValue("#0066CC");
        mockFlag(flag);

        FlagEvaluationResponse res = service.evaluateFlag(req("checkout-color", "PRODUCTION"));

        assertThat(res.getEnabled()).isTrue();
        assertThat(res.getValue()).isEqualTo("#0066CC");
    }

    @Test
    void evaluateFlag_rollout100Percent_userIdProvided_inRollout() {
        FeatureFlag flag = flag("beta", "DEV", true);
        flag.setRolloutPercentage(100);
        mockFlag(flag);

        FlagEvaluationRequest request = req("beta", "DEV");
        request.setUserId("any-user");
        FlagEvaluationResponse res = service.evaluateFlag(request);

        assertThat(res.getEnabled()).isTrue();
        assertThat(res.getReason()).isEqualTo("ROLLOUT_PERCENTAGE");
    }

    @Test
    void evaluateFlag_rollout0Percent_userIdProvided_excluded() {
        FeatureFlag flag = flag("beta", "DEV", true);
        flag.setRolloutPercentage(0);
        mockFlag(flag);

        FlagEvaluationRequest request = req("beta", "DEV");
        request.setUserId("any-user");
        FlagEvaluationResponse res = service.evaluateFlag(request);

        assertThat(res.getEnabled()).isFalse();
        assertThat(res.getReason()).isEqualTo("ROLLOUT_EXCLUDED");
    }

    @Test
    void evaluateFlag_rolloutSet_noUserId_skipsRollout_returnsEnabled() {
        FeatureFlag flag = flag("beta", "DEV", true);
        flag.setRolloutPercentage(50);
        mockFlag(flag);

        FlagEvaluationResponse res = service.evaluateFlag(req("beta", "DEV")); // no userId

        assertThat(res.getEnabled()).isTrue();
        assertThat(res.getReason()).isEqualTo("FLAG_ENABLED");
    }

    @Test
    void evaluateFlag_rolloutDeterministic_sameUserAlwaysSameResult() {
        FeatureFlag flag = flag("rollout-flag", "PRODUCTION", true);
        flag.setRolloutPercentage(50);
        mockFlag(flag);

        FlagEvaluationRequest r1 = req("rollout-flag", "PRODUCTION");
        r1.setUserId("stable-user-id");
        FlagEvaluationRequest r2 = req("rollout-flag", "PRODUCTION");
        r2.setUserId("stable-user-id");

        boolean first  = service.evaluateFlag(r1).getEnabled();
        boolean second = service.evaluateFlag(r2).getEnabled();

        assertThat(first).isEqualTo(second);
    }

    // ── Targeting rules ───────────────────────────────────────────────────────

    @Test
    void evaluateFlag_targetingMatch_continuesNormally() {
        FeatureFlag flag = flag("ent", "PRODUCTION", true);
        flag.setTargetingRules("{\"match\":\"all\",\"rules\":[{\"attribute\":\"plan\",\"operator\":\"eq\",\"value\":\"pro\"}]}");
        mockFlag(flag);

        FlagEvaluationRequest request = req("ent", "PRODUCTION");
        request.setAttributes(Map.of("plan", "pro"));

        FlagEvaluationResponse res = service.evaluateFlag(request);

        assertThat(res.getEnabled()).isTrue();
        assertThat(res.getReason()).isEqualTo("FLAG_ENABLED");
    }

    @Test
    void evaluateFlag_targetingNoMatch_returnsTargetingNoMatch() {
        FeatureFlag flag = flag("ent", "PRODUCTION", true);
        flag.setTargetingRules("{\"match\":\"all\",\"rules\":[{\"attribute\":\"plan\",\"operator\":\"eq\",\"value\":\"pro\"}]}");
        mockFlag(flag);

        FlagEvaluationRequest request = req("ent", "PRODUCTION");
        request.setAttributes(Map.of("plan", "free"));

        FlagEvaluationResponse res = service.evaluateFlag(request);

        assertThat(res.getEnabled()).isFalse();
        assertThat(res.getReason()).isEqualTo("TARGETING_NO_MATCH");
    }

    @Test
    void evaluateFlag_targetingDefined_noAttributesProvided_fallsThroughToEnabled() {
        FeatureFlag flag = flag("ent", "PRODUCTION", true);
        flag.setTargetingRules("{\"match\":\"all\",\"rules\":[{\"attribute\":\"plan\",\"operator\":\"eq\",\"value\":\"pro\"}]}");
        mockFlag(flag);

        FlagEvaluationResponse res = service.evaluateFlag(req("ent", "PRODUCTION")); // no attributes

        assertThat(res.getEnabled()).isTrue();
        assertThat(res.getReason()).isEqualTo("FLAG_ENABLED");
    }

    @Test
    void evaluateFlag_targetingAnyMode_oneRuleMatches_returnsEnabled() {
        FeatureFlag flag = flag("any-flag", "PRODUCTION", true);
        flag.setTargetingRules("{\"match\":\"any\",\"rules\":["
            + "{\"attribute\":\"plan\",\"operator\":\"eq\",\"value\":\"pro\"},"
            + "{\"attribute\":\"country\",\"operator\":\"eq\",\"value\":\"DE\"}"
            + "]}");
        mockFlag(flag);

        FlagEvaluationRequest request = req("any-flag", "PRODUCTION");
        request.setAttributes(Map.of("plan", "free", "country", "DE")); // only country matches

        FlagEvaluationResponse res = service.evaluateFlag(request);

        assertThat(res.getEnabled()).isTrue();
    }

    @Test
    void evaluateFlag_targetingInvalidJson_gracefullyFallsThrough() {
        FeatureFlag flag = flag("flag", "DEV", true);
        flag.setTargetingRules("not-valid-json{{{{");
        mockFlag(flag);

        FlagEvaluationRequest request = req("flag", "DEV");
        request.setAttributes(Map.of("plan", "pro"));

        // Should not throw; falls through to FLAG_ENABLED when rules can't be parsed
        FlagEvaluationResponse res = service.evaluateFlag(request);

        assertThat(res.getEnabled()).isFalse(); // no match from invalid rules
        assertThat(res.getReason()).isEqualTo("TARGETING_NO_MATCH");
    }

    @ParameterizedTest(name = "operator={0}, ruleValue={1}, userValue={2} => match={3}")
    @MethodSource("targetingOperatorCases")
    void evaluateFlag_targetingOperators(String operator, String ruleValue, String userValue, boolean expectedMatch) {
        FeatureFlag flag = flag("flag", "DEV", true);
        flag.setTargetingRules(String.format(
            "{\"match\":\"all\",\"rules\":[{\"attribute\":\"x\",\"operator\":\"%s\",\"value\":\"%s\"}]}",
            operator, ruleValue));
        mockFlag(flag);

        FlagEvaluationRequest request = req("flag", "DEV");
        request.setAttributes(Map.of("x", userValue));

        assertThat(service.evaluateFlag(request).getEnabled()).isEqualTo(expectedMatch);
    }

    static Stream<Arguments> targetingOperatorCases() {
        return Stream.of(
            Arguments.of("eq",         "pro",      "pro",       true),
            Arguments.of("eq",         "pro",      "free",      false),
            Arguments.of("neq",        "pro",      "free",      true),
            Arguments.of("neq",        "pro",      "pro",       false),
            Arguments.of("contains",   "acme",     "user@acme.com",  true),
            Arguments.of("contains",   "acme",     "user@other.com", false),
            Arguments.of("startsWith", "beta-",    "beta-user", true),
            Arguments.of("startsWith", "beta-",    "prod-user", false),
            Arguments.of("endsWith",   "@acme.com","user@acme.com",  true),
            Arguments.of("endsWith",   "@acme.com","user@other.com", false),
            Arguments.of("in",         "DE,US,UK", "DE",        true),
            Arguments.of("in",         "DE,US,UK", "FR",        false),
            Arguments.of("notIn",      "DE,US",    "FR",        true),
            Arguments.of("notIn",      "DE,US",    "DE",        false),
            Arguments.of("gt",         "18",       "21",        true),
            Arguments.of("gt",         "18",       "16",        false),
            Arguments.of("lt",         "100",      "50",        true),
            Arguments.of("lt",         "100",      "150",       false),
            Arguments.of("gte",        "18",       "18",        true),
            Arguments.of("gte",        "18",       "17",        false),
            Arguments.of("lte",        "100",      "100",       true),
            Arguments.of("lte",        "100",      "101",       false)
        );
    }

    // ── evaluateBulk ─────────────────────────────────────────────────────────

    @Test
    void evaluateBulk_evaluatesAllKeys_withSharedAttributes() {
        FeatureFlag flagA = flag("flag-a", "PRODUCTION", true);
        FeatureFlag flagB = flag("flag-b", "PRODUCTION", false);
        when(flagRepository.findByFlagKeyAndEnvironment("flag-a", "PRODUCTION")).thenReturn(Optional.of(flagA));
        when(flagRepository.findByFlagKeyAndEnvironment("flag-b", "PRODUCTION")).thenReturn(Optional.of(flagB));

        BulkEvaluationRequest bulk = new BulkEvaluationRequest();
        bulk.setFlagKeys(List.of("flag-a", "flag-b"));
        bulk.setEnvironment("PRODUCTION");
        bulk.setAttributes(Map.of("plan", "pro"));

        Map<String, FlagEvaluationResponse> results = service.evaluateBulk(bulk);

        assertThat(results).containsKeys("flag-a", "flag-b");
        assertThat(results.get("flag-a").getEnabled()).isTrue();
        assertThat(results.get("flag-b").getEnabled()).isFalse();
    }

    @Test
    void evaluateBulk_deduplicatesKeys() {
        mockFlag(flag("dup", "DEV", true));

        BulkEvaluationRequest bulk = new BulkEvaluationRequest();
        bulk.setFlagKeys(List.of("dup", "dup", "dup"));
        bulk.setEnvironment("DEV");

        Map<String, FlagEvaluationResponse> results = service.evaluateBulk(bulk);

        assertThat(results).hasSize(1);
        verify(flagRepository, times(1)).findByFlagKeyAndEnvironment("dup", "DEV");
    }

    // ── Flag mutation side effects ────────────────────────────────────────────

    @Test
    void createFlag_auditsAndDispatchesWebhook() {
        FeatureFlagDTO dto = new FeatureFlagDTO();
        dto.setFlagKey("new-flag");
        dto.setName("New Flag");
        dto.setEnvironment("DEV");
        dto.setEnabled(false);

        FeatureFlag saved = flag("new-flag", "DEV", false);
        saved.setId(1L);
        when(flagRepository.existsByFlagKeyAndEnvironment("new-flag", "DEV")).thenReturn(false);
        when(flagRepository.save(any())).thenReturn(saved);

        service.createFlag(dto, "admin");

        verify(auditService).logAction(eq("FeatureFlag"), eq(1L), eq("CREATE"), eq("admin"), isNull(), anyString());
        verify(webhookService).dispatch(eq("FLAG_CREATED"), any(), eq("admin"));
        verify(flagChangePublisher).publish(eq("DEV"), eq("FLAG_CHANGED"), anyMap());
    }

    @Test
    void createFlag_duplicateKey_throwsIllegalArgument() {
        FeatureFlagDTO dto = new FeatureFlagDTO();
        dto.setFlagKey("existing");
        dto.setName("Existing");
        dto.setEnvironment("DEV");
        when(flagRepository.existsByFlagKeyAndEnvironment("existing", "DEV")).thenReturn(true);

        assertThatThrownBy(() -> service.createFlag(dto, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void promoteFlag_promotedFlagAlwaysStartsDisabled() {
        FeatureFlag source = flag("my-flag", "DEVELOPMENT", true); // source is enabled
        source.setId(1L);
        when(flagRepository.findById(1L)).thenReturn(Optional.of(source));
        when(flagRepository.existsByFlagKeyAndEnvironment("my-flag", "STAGING")).thenReturn(false);

        FeatureFlag promoted = flag("my-flag", "STAGING", false);
        promoted.setId(2L);
        when(flagRepository.save(argThat(f -> "STAGING".equals(f.getEnvironment()))))
            .thenReturn(promoted);

        FeatureFlagDTO result = service.promoteFlag(1L, "STAGING", "admin");

        verify(flagRepository).save(argThat(f -> !f.getEnabled())); // must be saved disabled
    }

    @Test
    void validateFlagDTO_rolloutOutOfRange_throwsIllegalArgument() {
        FeatureFlagDTO dto = new FeatureFlagDTO();
        dto.setFlagKey("flag");
        dto.setName("Flag");
        dto.setRolloutPercentage(150); // invalid

        assertThatThrownBy(() -> service.createFlag(dto, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Rollout percentage");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FeatureFlag flag(String key, String env, boolean enabled) {
        FeatureFlag f = new FeatureFlag();
        f.setFlagKey(key);
        f.setName(key);
        f.setEnvironment(env);
        f.setEnabled(enabled);
        f.setDefaultValue(false);
        f.setFlagType("BOOLEAN");
        return f;
    }

    private void mockFlag(FeatureFlag flag) {
        when(flagRepository.findByFlagKeyAndEnvironment(flag.getFlagKey(), flag.getEnvironment()))
            .thenReturn(Optional.of(flag));
    }

    private FlagEvaluationRequest req(String flagKey, String env) {
        FlagEvaluationRequest r = new FlagEvaluationRequest();
        r.setFlagKey(flagKey);
        r.setEnvironment(env);
        return r;
    }
}
