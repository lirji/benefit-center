package com.lrj.benefit.web;

import com.github.dockerjava.api.exception.NotFoundException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

/** 测试仅检查实际所需镜像，避免Docker全量镜像枚举阻塞全部数据库验证。 */
public final class SingleImagePullPolicy implements ImagePullPolicy {
    /** 保持缺失才拉取的默认语义；其他Docker错误原样失败，不能伪装镜像存在。 */
    @Override public boolean shouldPull(DockerImageName imageName) {
        try {
            DockerClientFactory.lazyClient().inspectImageCmd(imageName.asCanonicalNameString()).exec();
            return false;
        } catch (NotFoundException missing) {
            return true;
        }
    }
}
