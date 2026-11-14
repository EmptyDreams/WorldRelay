ARG BASE_IMAGE=container-registry.oracle.com/graalvm/native-image:25-ol8
FROM ${BASE_IMAGE}

# OL8 精简镜像提供 C.utf8；继承的 en_US.UTF-8 缺少 locale 数据时会回退为 ASCII。
# Native Image 会固化构建时的文件名编码，必须使用镜像中实际存在的 UTF-8 locale。
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

# 静态库和头文件由同一 OL8 仓库提供，保持 glibc 2.28 构建基线。
RUN (rpm -q zlib-static >/dev/null 2>&1 || \
     microdnf --enablerepo ol8_codeready_builder install -y zlib-static) \
    && test -f /usr/lib64/libz.a \
    && microdnf clean all
