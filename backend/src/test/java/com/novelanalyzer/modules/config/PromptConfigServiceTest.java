package com.novelanalyzer.modules.config;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.modules.config.dto.AdminPromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.dto.PromptConfigUpdateRequest;
import com.novelanalyzer.modules.config.model.PromptConfigEntity;
import com.novelanalyzer.modules.config.repository.PromptConfigRepository;
import com.novelanalyzer.modules.config.service.DefaultPromptContractCatalog;
import com.novelanalyzer.modules.config.service.PromptConfigService;
import com.novelanalyzer.modules.config.vo.AiModelRegistryModelVO;
import com.novelanalyzer.modules.config.vo.PromptConfigVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptConfigServiceTest {

    @Test
    void shouldSaveNewTemplateByPromptNameInsteadOfOverwritingDefault() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        when(repository.findByTypeAndName("deconstruct", "kimi-k2.5")).thenReturn(Optional.empty());
        when(repository.saveOrUpdate(any(PromptConfigEntity.class))).thenReturn(9L);
        when(repository.findByTypeAndName("deconstruct", "kimi-k2.5")).thenReturn(Optional.of(buildEntity(
            9L,
            "deconstruct",
            "kimi-k2.5",
            "Kimi 专属模板 {{content}}"
        )));

        PromptConfigUpdateRequest request = new PromptConfigUpdateRequest();
        request.setPromptType("deconstruct");
        request.setPromptName("kimi-k2.5");
        request.setPromptContent("Kimi 专属模板 {{content}}");
        request.setModelName("kimi-k2.5");

        PromptConfigVO saved = service.save(request);

        assertThat(saved.getId()).isEqualTo(9L);
        assertThat(saved.getPromptName()).isEqualTo("kimi-k2.5");
        assertThat(saved.getPromptContent()).isEqualTo("Kimi 专属模板 {{content}}");
    }

    @Test
    void shouldRejectTemplateWithoutContentPlaceholder() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        PromptConfigUpdateRequest request = new PromptConfigUpdateRequest();
        request.setPromptType("deconstruct");
        request.setPromptName("kimi-k2.5");
        request.setPromptContent("不带占位符");
        request.setModelName("kimi-k2.5");

        assertThatThrownBy(() -> service.save(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("promptContent must contain {{content}} placeholder");
    }

    @Test
    void shouldRejectRenamingDefaultTemplate() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        when(repository.findByTypeAndName("deconstruct", "default")).thenReturn(Optional.of(buildEntity(
            1L,
            "deconstruct",
            "default",
            "Default {{content}}"
        )));

        PromptConfigUpdateRequest request = new PromptConfigUpdateRequest();
        request.setPromptType("deconstruct");
        request.setPromptName("renamed-default");
        request.setPromptContent("Default {{content}}");
        request.setModelName("deepseek-chat");

        assertThatThrownBy(() -> service.saveDefaultTemplate("deconstruct", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("default template name cannot be changed");
    }

    @Test
    void shouldDeleteNonDefaultTemplateWhenNotBound() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        when(repository.findActiveByTypeAndName("deconstruct", "kimi-template")).thenReturn(Optional.of(buildEntity(
            9L,
            "deconstruct",
            "kimi-template",
            "Kimi {{content}}"
        )));

        service.deleteTemplate("deconstruct", "kimi-template", List.of());

        verify(repository).softDeleteById(9L);
    }

    @Test
    void shouldRejectDeletingDefaultTemplate() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        when(repository.findActiveByTypeAndName("deconstruct", "default")).thenReturn(Optional.of(buildEntity(
            1L,
            "deconstruct",
            "default",
            "Default {{content}}"
        )));

        assertThatThrownBy(() -> service.deleteTemplate("deconstruct", "default", List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("default template cannot be deleted");

        verify(repository, never()).softDeleteById(any());
    }

    @Test
    void shouldRejectDeletingLegacyNamedDefaultTemplateByIsDefaultFlag() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        PromptConfigEntity legacyDefault = buildEntity(
            1L,
            "deconstruct",
            "default-deconstruct",
            "Default {{content}}"
        );
        legacyDefault.setIsDefault(1);
        when(repository.findActiveByTypeAndName("deconstruct", "default-deconstruct")).thenReturn(Optional.of(legacyDefault));

        assertThatThrownBy(() -> service.deleteTemplate("deconstruct", "default-deconstruct", List.of()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("default template cannot be deleted");

        verify(repository, never()).softDeleteById(any());
    }

    @Test
    void shouldResolveLegacyDefaultAliasWhenReadingByType() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        PromptConfigEntity legacyDefault = buildEntity(
            1L,
            "deconstruct",
            "default-deconstruct",
            "Legacy default {{content}}"
        );
        legacyDefault.setIsDefault(1);
        when(repository.findActiveByTypeAndName("deconstruct", "default-deconstruct"))
            .thenReturn(Optional.of(legacyDefault));

        PromptConfigVO config = service.getByType("deconstruct");

        assertThat(config.getPromptName()).isEqualTo("default-deconstruct");
        assertThat(config.getPromptContent()).isEqualTo("Legacy default {{content}}");
    }

    @Test
    void shouldSaveLegacyDefaultAliasIntoTypeScopedDefaultTemplate() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        PromptConfigEntity legacyDefault = buildEntity(
            1L,
            "deconstruct",
            "default-deconstruct",
            "Old {{content}}"
        );
        legacyDefault.setIsDefault(1);
        when(repository.findActiveByTypeAndName("deconstruct", "default-deconstruct"))
            .thenReturn(Optional.of(legacyDefault));
        when(repository.findByTypeAndName("deconstruct", "default-deconstruct"))
            .thenReturn(Optional.of(legacyDefault));
        when(repository.saveOrUpdate(any(PromptConfigEntity.class))).thenReturn(1L);

        PromptConfigUpdateRequest request = new PromptConfigUpdateRequest();
        request.setPromptType("deconstruct");
        request.setPromptName("default");
        request.setPromptContent("Updated {{content}}");
        request.setModelName("deepseek-chat");
        request.setTemperature(0.55);
        request.setMaxTokens(1200);

        PromptConfigVO saved = service.save(request);

        assertThat(saved.getPromptName()).isEqualTo("default-deconstruct");
        assertThat(saved.getPromptContent()).isEqualTo("Updated {{content}}");
        assertThat(saved.getTemperature()).isEqualTo(0.55);
        assertThat(saved.getMaxTokens()).isEqualTo(1200);
    }

    @Test
    void shouldRejectDeletingTemplateWhenBoundByModel() {
        PromptConfigRepository repository = mock(PromptConfigRepository.class);
        PromptConfigService service = new PromptConfigService(repository, new DefaultPromptContractCatalog());

        when(repository.findActiveByTypeAndName("deconstruct", "kimi-template")).thenReturn(Optional.of(buildEntity(
            9L,
            "deconstruct",
            "kimi-template",
            "Kimi {{content}}"
        )));

        AiModelRegistryModelVO model = new AiModelRegistryModelVO();
        model.setModelKey("kimi-k2.5");
        model.setEnabled(true);
        model.setPromptBindings(Map.of("deconstruct", "kimi-template"));

        assertThatThrownBy(() -> service.deleteTemplate("deconstruct", "kimi-template", List.of(model)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("template is bound to model");

        verify(repository, never()).softDeleteById(any());
    }

    private PromptConfigEntity buildEntity(Long id, String promptType, String promptName, String promptContent) {
        PromptConfigEntity entity = new PromptConfigEntity();
        entity.setId(id);
        entity.setPromptType(promptType);
        entity.setPromptName(promptName);
        entity.setPromptContent(promptContent);
        entity.setModelName("kimi-k2.5");
        entity.setStatus(1);
        entity.setIsDefault("default".equals(promptName) ? 1 : 0);
        return entity;
    }
}
