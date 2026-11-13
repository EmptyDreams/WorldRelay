ARG BASE_IMAGE=container-registry.oracle.com/graalvm/native-image:25-ol8
FROM ${BASE_IMAGE}

# 静态库和头文件由同一 OL8 仓库提供，保持 glibc 2.28 构建基线。
RUN (rpm -q zlib-static >/dev/null 2>&1 || \
     microdnf --enablerepo ol8_codeready_builder install -y zlib-static) \
    && test -f /usr/lib64/libz.a \
    && microdnf clean all
