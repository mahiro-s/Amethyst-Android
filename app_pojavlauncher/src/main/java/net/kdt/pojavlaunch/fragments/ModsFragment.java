package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import app.mcrust.ModsEntry;
import app.mcrust.core.Profile;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

/**
 * Моды выбранного профиля: каталог Modrinth и CurseForge с зависимостями, установленные моды
 * (вкл/выкл/удалить) и обновление всех сразу. Сама логика живёт в app.mcrust (общий код с отдельным приложением).
 */
public class ModsFragment extends Fragment {
    public static final String TAG = "ModsFragment";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        MinecraftProfile selected = LauncherProfiles.getCurrentProfile();
        String versionId = selected.lastVersionId;
        if (versionId == null || versionId.startsWith("latest-")) {
            TextView t = new TextView(requireContext());
            t.setPadding(32, 32, 32, 32);
            t.setText("Выберите установленную версию с Fabric, Quilt, NeoForge или Forge: у чистой игры модов нет.");
            return t;
        }
        Profile profile = Profile.fromInstalledVersion(new File(Tools.DIR_HOME_VERSION), versionId);
        profile.name = selected.name != null && !selected.name.isEmpty() ? selected.name : versionId;
        String cfKey = getString(R.string.curseforge_api_key);
        if ("DUMMY".equals(cfKey)) cfKey = "";
        return ModsEntry.create(requireActivity(), requireContext().getCacheDir(), cfKey, profile,
                Tools.getGameDirPath(selected), () -> Tools.removeCurrentFragment(requireActivity()));
    }
}
