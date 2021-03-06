/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.download.game;

import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import org.jackhuang.hmcl.download.AbstractDependencyManager;
import org.jackhuang.hmcl.game.AssetIndex;
import org.jackhuang.hmcl.game.AssetIndexInfo;
import org.jackhuang.hmcl.game.AssetObject;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.Logging;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 *
 * @author huangyuhui
 */
public final class GameAssetDownloadTask extends Task<Void> {
    
    private final AbstractDependencyManager dependencyManager;
    private final Version version;
    private final AssetIndexInfo assetIndexInfo;
    private final File assetIndexFile;
    private final boolean integrityCheck;
    private final List<Task<?>> dependents = new LinkedList<>();
    private final List<Task<?>> dependencies = new LinkedList<>();

    /**
     * Constructor.
     *
     * @param dependencyManager the dependency manager that can provides {@link org.jackhuang.hmcl.game.GameRepository}
     * @param version the game version
     */
    public GameAssetDownloadTask(AbstractDependencyManager dependencyManager, Version version, boolean forceDownloadingIndex, boolean integrityCheck) {
        this.dependencyManager = dependencyManager;
        this.version = version.resolve(dependencyManager.getGameRepository());
        this.assetIndexInfo = this.version.getAssetIndex();
        this.assetIndexFile = dependencyManager.getGameRepository().getIndexFile(version.getId(), assetIndexInfo.getId());
        this.integrityCheck = integrityCheck;

        if (!assetIndexFile.exists() || forceDownloadingIndex) {
            dependents.add(new GameAssetIndexDownloadTask(dependencyManager, this.version));
        } else {
            try {
                JsonUtils.fromNonNullJson(FileUtils.readText(assetIndexFile), AssetIndex.class);
            } catch (IOException | JsonParseException e) {
                dependents.add(new GameAssetIndexDownloadTask(dependencyManager, this.version));
            }
        }
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public void execute() throws Exception {
        AssetIndex index;
        try {
            index = JsonUtils.fromNonNullJson(FileUtils.readText(assetIndexFile), AssetIndex.class);
        } catch (IOException | JsonParseException e) {
            throw new GameAssetIndexDownloadTask.GameAssetIndexMalformedException();
        }

        int progress = 0;
        for (AssetObject assetObject : index.getObjects().values()) {
            if (isCancelled())
                throw new InterruptedException();

            File file = dependencyManager.getGameRepository().getAssetObject(version.getId(), assetIndexInfo.getId(), assetObject);
            boolean download = !file.isFile();
            try {
                if (!download && integrityCheck && !assetObject.validateChecksum(file.toPath(), true))
                    download = true;
            } catch (IOException e) {
                Logging.LOG.log(Level.WARNING, "Unable to calc hash value of file " + file.toPath(), e);
            }
            if (download) {
                List<URL> urls = dependencyManager.getPreferredDownloadProviders().stream()
                        .map(downloadProvider -> downloadProvider.getAssetBaseURL() + assetObject.getLocation())
                        .map(NetworkUtils::toURL)
                        .collect(Collectors.toList());

                FileDownloadTask task = new FileDownloadTask(urls, file, new FileDownloadTask.IntegrityCheck("SHA-1", assetObject.getHash()));
                task.setName(assetObject.getHash());
                dependencies.add(task
                        .setCacheRepository(dependencyManager.getCacheRepository())
                        .setCaching(true)
                        .setCandidate(dependencyManager.getCacheRepository().getCommonDirectory()
                                .resolve("assets").resolve("objects").resolve(assetObject.getLocation())).withCounter());
            } else {
                dependencyManager.getCacheRepository().tryCacheFile(file.toPath(), CacheRepository.SHA1, assetObject.getHash());
            }

            updateProgress(++progress, index.getObjects().size());
        }

        if (!dependencies.isEmpty()) {
            getProperties().put("total", dependencies.size());
        }
    }

    public static final boolean DOWNLOAD_INDEX_FORCIBLY = true;
    public static final boolean DOWNLOAD_INDEX_IF_NECESSARY = false;
}
