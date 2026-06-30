package io.bunnycal.admin.settings;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
    List<AppSetting> findByCategoryOrderByKeyAsc(SettingCategory category);
    List<AppSetting> findAllByOrderByCategoryAscKeyAsc();
}
