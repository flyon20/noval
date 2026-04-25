package com.novelanalyzer.modules.config;

import com.novelanalyzer.modules.config.model.PromptPublishItemEntity;
import com.novelanalyzer.modules.config.model.PromptPublishVersionEntity;
import com.novelanalyzer.modules.config.model.UserPromptBindingEntity;
import com.novelanalyzer.modules.config.model.UserPromptEffectiveHistoryEntity;
import com.novelanalyzer.modules.config.repository.PromptPublishRepository;
import com.novelanalyzer.modules.config.repository.UserPromptBindingRepository;
import com.novelanalyzer.modules.config.repository.UserPromptEffectiveHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:promptgovrepo;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class PromptGovernanceRepositoryTest {

    @Autowired
    private PromptPublishRepository promptPublishRepository;
    @Autowired
    private UserPromptBindingRepository userPromptBindingRepository;
    @Autowired
    private UserPromptEffectiveHistoryRepository userPromptEffectiveHistoryRepository;

    @Test
    void shouldSaveAndQueryPromptPublishVersionAndItems() {
        PromptPublishVersionEntity version = new PromptPublishVersionEntity();
        version.setVersionNo(2L);
        version.setPublishedBy(1L);
        version.setPublishNote("publish v2");

        Long versionId = promptPublishRepository.saveVersion(version);

        PromptPublishItemEntity deconstruct = new PromptPublishItemEntity();
        deconstruct.setPublishVersionId(versionId);
        deconstruct.setPromptType("deconstruct");
        deconstruct.setPromptConfigId(1L);
        deconstruct.setPromptName("default");
        promptPublishRepository.saveItem(deconstruct);

        PromptPublishItemEntity theme = new PromptPublishItemEntity();
        theme.setPublishVersionId(versionId);
        theme.setPromptType("theme");
        theme.setPromptConfigId(4L);
        theme.setPromptName("default");
        promptPublishRepository.saveItem(theme);

        Optional<PromptPublishVersionEntity> loadedVersion = promptPublishRepository.findVersionById(versionId);
        List<PromptPublishItemEntity> loadedItems = promptPublishRepository.findItemsByVersionId(versionId);

        assertThat(loadedVersion).isPresent();
        assertThat(loadedVersion.get().getVersionNo()).isEqualTo(2L);
        assertThat(loadedItems).hasSize(2);
        assertThat(loadedItems).extracting(PromptPublishItemEntity::getPromptType)
            .containsExactly("deconstruct", "theme");
    }

    @Test
    void shouldSaveAndResolveActiveUserPromptBinding() {
        UserPromptBindingEntity binding = new UserPromptBindingEntity();
        binding.setUserId(2L);
        binding.setPromptType("deconstruct");
        binding.setBindingMode("USER_COPY");
        binding.setBoundPromptConfigId(101L);
        binding.setLastSelectedPromptConfigId(101L);
        binding.setEffectivePromptConfigId(101L);
        binding.setFallbackWarning(null);
        binding.setStatus(1);

        Long bindingId = userPromptBindingRepository.saveOrUpdate(binding);

        Optional<UserPromptBindingEntity> loaded = userPromptBindingRepository.findActiveBinding(2L, "deconstruct");

        assertThat(bindingId).isNotNull();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getBindingMode()).isEqualTo("USER_COPY");
        assertThat(loaded.get().getBoundPromptConfigId()).isEqualTo(101L);
        assertThat(loaded.get().getEffectivePromptConfigId()).isEqualTo(101L);
    }

    @Test
    void shouldSaveAndQueryLatestEffectiveHistory() {
        UserPromptEffectiveHistoryEntity earlier = new UserPromptEffectiveHistoryEntity();
        earlier.setUserId(2L);
        earlier.setPromptType("theme");
        earlier.setPublishVersionId(1L);
        earlier.setBindingMode("GLOBAL");
        earlier.setBoundPromptConfigId(null);
        earlier.setEffectivePromptConfigId(4L);
        earlier.setEffectiveSource("GLOBAL_PUBLISHED");
        earlier.setPreviousEffectivePromptConfigId(null);
        earlier.setSelectedModelKey("deepseek-chat");
        earlier.setFallback(0);
        userPromptEffectiveHistoryRepository.save(earlier);

        UserPromptEffectiveHistoryEntity latest = new UserPromptEffectiveHistoryEntity();
        latest.setUserId(2L);
        latest.setPromptType("theme");
        latest.setPublishVersionId(1L);
        latest.setBindingMode("USER_COPY");
        latest.setBoundPromptConfigId(104L);
        latest.setEffectivePromptConfigId(104L);
        latest.setEffectiveSource("USER_COPY");
        latest.setPreviousEffectivePromptConfigId(4L);
        latest.setSelectedModelKey("kimi-k2.5");
        latest.setFallback(0);
        userPromptEffectiveHistoryRepository.save(latest);

        Optional<UserPromptEffectiveHistoryEntity> loaded = userPromptEffectiveHistoryRepository.findLatestByUserIdAndPromptType(2L, "theme");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getEffectivePromptConfigId()).isEqualTo(104L);
        assertThat(loaded.get().getEffectiveSource()).isEqualTo("USER_COPY");
        assertThat(loaded.get().getSelectedModelKey()).isEqualTo("kimi-k2.5");
        assertThat(loaded.get().getPreviousEffectivePromptConfigId()).isEqualTo(4L);
    }
}
