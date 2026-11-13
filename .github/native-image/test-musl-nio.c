#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "NIO probe line %d: %s (errno=%d)\n", __LINE__, #condition, errno); \
        return 1; \
    } \
} while (0)

/* 验证 JDK 所需符号、NOFOLLOW 拒绝链接，以及父目录被替换后仍按原句柄读取。 */
int main(int argc, char **argv) {
    CHECK(argc == 2);
    int (*nio_openat)(int, const char *, int, ...) = dlsym(RTLD_DEFAULT, "openat64");
    int (*nio_fstatat)(int, const char *, struct stat *, int) = dlsym(RTLD_DEFAULT, "fstatat64");
    int (*nio_unlinkat)(int, const char *, int) = dlsym(RTLD_DEFAULT, "unlinkat");
    int (*nio_renameat)(int, const char *, int, const char *) = dlsym(RTLD_DEFAULT, "renameat");
    DIR *(*nio_fdopendir)(int) = dlsym(RTLD_DEFAULT, "fdopendir");
    CHECK(nio_openat && nio_fstatat && nio_unlinkat && nio_renameat && nio_fdopendir);
    CHECK(dlsym(RTLD_DEFAULT, "worldrelay_missing_symbol") == NULL);

    char root[PATH_MAX], source[PATH_MAX], moved[PATH_MAX], outside[PATH_MAX], link[PATH_MAX];
    CHECK(snprintf(root, sizeof(root), "%s/nio-XXXXXX", argv[1]) < (int)sizeof(root));
    CHECK(mkdtemp(root));
    CHECK(snprintf(source, sizeof(source), "%s/source", root) < (int)sizeof(source));
    CHECK(snprintf(moved, sizeof(moved), "%s/moved", root) < (int)sizeof(moved));
    CHECK(snprintf(outside, sizeof(outside), "%s/outside", root) < (int)sizeof(outside));
    CHECK(snprintf(link, sizeof(link), "%s/source/link", root) < (int)sizeof(link));
    CHECK(mkdir(source, 0700) == 0 && mkdir(outside, 0700) == 0);
    int directory = open(source, O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
    CHECK(directory >= 0);
    int file = nio_openat(directory, "level.dat", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    CHECK(file >= 0 && write(file, "world", 5) == 5 && close(file) == 0);
    struct stat attributes;
    CHECK(nio_fstatat(directory, "level.dat", &attributes, AT_SYMLINK_NOFOLLOW) == 0);
    CHECK(S_ISREG(attributes.st_mode) && attributes.st_size == 5);
    CHECK(nio_renameat(directory, "level.dat", directory, "renamed.dat") == 0);
    CHECK(symlink(outside, link) == 0);
    CHECK(nio_fstatat(directory, "link", &attributes, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(attributes.st_mode));
    CHECK(nio_openat(directory, "link", O_RDONLY | O_DIRECTORY | O_NOFOLLOW) == -1);
    CHECK(errno == ELOOP || errno == ENOTDIR);

    CHECK(rename(source, moved) == 0 && symlink(outside, source) == 0);
    file = nio_openat(directory, "renamed.dat", O_RDONLY | O_NOFOLLOW);
    char bytes[5];
    CHECK(file >= 0 && read(file, bytes, sizeof(bytes)) == 5 && close(file) == 0);
    CHECK(!memcmp(bytes, "world", 5));
    DIR *stream = nio_fdopendir(dup(directory));
    CHECK(stream);
    int found = 0;
    struct dirent *entry;
    while ((entry = readdir(stream))) found |= !strcmp(entry->d_name, "renamed.dat");
    CHECK(found && closedir(stream) == 0);
    CHECK(nio_unlinkat(directory, "renamed.dat", 0) == 0 && nio_unlinkat(directory, "link", 0) == 0);
    CHECK(close(directory) == 0 && unlink(source) == 0);
    CHECK(rmdir(moved) == 0 && rmdir(outside) == 0 && rmdir(root) == 0);
    puts("Static musl NIO: secure directory functions, no-follow and directory replacement passed");
    return 0;
}
