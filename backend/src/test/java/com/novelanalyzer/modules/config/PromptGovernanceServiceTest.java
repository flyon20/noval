package com.novelanalyzer.modules.config;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.config.dto.AdminPromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.dto.PromptPublishRequest;
import com.novelanalyzer.modules.config.dto.UserPromptBindingUpdateRequest;
import com.novelanalyzer.modules.config.dto.UserPromptCopyCreateRequest;
import com.novelanalyzer.modules.config.dto.UserPromptCopyUpdateRequest;
import com.novelanalyzer.modules.config.service.PromptGovernanceService;
import com.novelanalyzer.modules.config.vo.PromptPublishVersionVO;
import com.novelanalyzer.modules.config.vo.UserPromptBindingVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:promptgovsvc;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
    }
)
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase5-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql",
        "classpath:sql/phase4-data-h2.sql",
        "classpath:sql/phase5-data-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class PromptGovernanceServiceTest {

    @Autowired
    private PromptGovernanceService promptGovernanceService;

    @Test
    void shouldPublishSelectedSystemTemplatesAsNewGlobalVersion() {
        PromptPublishRequest request = new PromptPublishRequest();
        request.setPublishNote("publish v2");
        request.setSelections(java.util.List.of(
            selection("deconstruct", "default", 1L),
            selection("structure", "default", 2L),
            selection("plot", "default", 3L),
            selection("theme", "default", 4L)
        ));

        PromptPublishVersionVO version = promptGovernanceService.publish(request, 1L);

        assertThat(version.getVersionNo()).isEqualTo(2L);
        assertThat(version.getPublishedBy()).isEqualTo(1L);
        assertThat(version.getItems()).hasSize(4);
        assertThat(version.getItems()).extracting(PromptPublishVersionVO.PromptPublishItemVO::getPromptType)
            .containsExactly("deconstruct", "plot", "structure", "theme");
    }

    @Test
    void shouldRejectPublishWhenSelectionsAreIncomplete() {
        PromptPublishRequest request = new PromptPublishRequest();
        request.setSelections(java.util.List.of(
            selection("deconstruct", "default", 1L)
        ));

        assertThatThrownBy(() -> promptGovernanceService.publish(request, 1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("four prompt type selections");
    }

    @Test
    void shouldCreateAndUpdateUserCopy() {
        UserPromptCopyCreateRequest createRequest = new UserPromptCopyCreateRequest();
        createRequest.setPromptType("deconstruct");
        createRequest.setSourcePromptConfigId(1L);
        createRequest.setCopyName("writer-copy");

        Long createdId = promptGovernanceService.createUserCopy(2L, createRequest).getId();

        UserPromptCopyUpdateRequest updateRequest = new UserPromptCopyUpdateRequest();
        updateRequest.setPromptType("deconstruct");
        updateRequest.setPromptConfigId(createdId);
        updateRequest.setPromptName("writer-copy-v2");
        updateRequest.setPromptContent("USER {{content}}");
        updateRequest.setModelName("kimi-k2.5");
        updateRequest.setTemperature(0.8);
        updateRequest.setMaxTokens(4096);

        var updated = promptGovernanceService.updateUserCopy(2L, updateRequest);

        assertThat(updated.getId()).isEqualTo(createdId);
        assertThat(updated.getPromptName()).isEqualTo("writer-copy-v2");
        assertThat(updated.getEditableScope()).isEqualTo("USER");
    }

    @Test
    void shouldRejectUserCopyWithContractFieldEdits() {
        UserPromptCopyCreateRequest createRequest = new UserPromptCopyCreateRequest();
        createRequest.setPromptType("deconstruct");
        createRequest.setSourcePromptConfigId(1L);
        createRequest.setCopyName("writer-copy");

        assertThat(promptGovernanceService.createUserCopy(2L, createRequest).getId()).isNotNull();
    }

    @Test
    void shouldResolveGlobalBindingByDefault() {
        UserPromptBindingVO binding = promptGovernanceService.getUserBinding(1L, "theme", "deepseek-chat");

        assertThat(binding.getPromptType()).isEqualTo("theme");
        assertThat(binding.getBindingMode()).isEqualTo("GLOBAL");
        assertThat(binding.getEffectivePromptConfigId()).isEqualTo(4L);
        assertThat(binding.getEffectiveSource()).isEqualTo(PromptGovernanceService.EFFECTIVE_SOURCE_GLOBAL_PUBLISHED);
    }

    @Test
    void shouldBindUserCopyAndUseItAsEffectivePrompt() {
        UserPromptCopyCreateRequest createRequest = new UserPromptCopyCreateRequest();
        createRequest.setPromptType("deconstruct");
        createRequest.setSourcePromptConfigId(1L);
        createRequest.setCopyName("writer-copy");
        Long copyId = promptGovernanceService.createUserCopy(2L, createRequest).getId();

        UserPromptCopyUpdateRequest updateRequest = new UserPromptCopyUpdateRequest();
        updateRequest.setPromptType("deconstruct");
        updateRequest.setPromptConfigId(copyId);
        updateRequest.setPromptName("writer-copy");
        updateRequest.setPromptContent("USER {{content}}");
        updateRequest.setModelName("kimi-k2.5");
        promptGovernanceService.updateUserCopy(2L, updateRequest);

        UserPromptBindingUpdateRequest bindingRequest = new UserPromptBindingUpdateRequest();
        bindingRequest.setPromptType("deconstruct");
        bindingRequest.setBindingMode("USER_COPY");
        bindingRequest.setBoundPromptConfigId(copyId);

        UserPromptBindingVO binding = promptGovernanceService.updateUserBinding(2L, bindingRequest, "kimi-k2.5");

        assertThat(binding.getBindingMode()).isEqualTo("USER_COPY");
        assertThat(binding.getBoundPromptConfigId()).isEqualTo(copyId);
        assertThat(binding.getEffectivePromptConfigId()).isEqualTo(copyId);
        assertThat(binding.getEffectiveSource()).isEqualTo(PromptGovernanceService.EFFECTIVE_SOURCE_USER_COPY);
    }

    @Test
    void shouldFallbackToGlobalWhenUserCopyIsMissing() {
        UserPromptBindingUpdateRequest bindingRequest = new UserPromptBindingUpdateRequest();
        bindingRequest.setPromptType("plot");
        bindingRequest.setBindingMode("USER_COPY");
        bindingRequest.setBoundPromptConfigId(9999L);

        UserPromptBindingVO binding = promptGovernanceService.updateUserBinding(2L, bindingRequest, "deepseek-chat");

        assertThat(binding.getEffectivePromptConfigId()).isEqualTo(3L);
        assertThat(binding.getEffectiveSource()).isEqualTo(PromptGovernanceService.EFFECTIVE_SOURCE_USER_COPY_FALLBACK_TO_GLOBAL);
        assertThat(binding.getFallbackWarning()).contains("回退");
    }

    @Test
    void shouldResolveSystemDefaultWhenPublishedPromptIsMissing() {
        PromptPublishRequest request = new PromptPublishRequest();
        request.setSelections(java.util.List.of(
            selection("deconstruct", "default", 1L),
            selection("structure", "default", 2L),
            selection("plot", "default", 3L),
            selection("theme", "default", 4L)
        ));
        promptGovernanceService.publish(request, 1L);

        var resolved = promptGovernanceService.resolveSystemDefault("plot");
        assertThat(resolved.getId()).isEqualTo(3L);
    }

    @Test
    void shouldPreferCanonicalSystemDefaultTemplate() {
        var resolved = promptGovernanceService.resolveSystemDefault("deconstruct");

        assertThat(resolved.getId()).isEqualTo(1L);
        assertThat(resolved.getPromptName()).isEqualTo("default");
    }

    private PromptPublishRequest.PromptPublishSelectionItem selection(String promptType, String promptName, Long promptConfigId) {
        PromptPublishRequest.PromptPublishSelectionItem item = new PromptPublishRequest.PromptPublishSelectionItem();
        item.setPromptType(promptType);
        item.setPromptName(promptName);
        item.setPromptConfigId(promptConfigId);
        return item;
    }
}
