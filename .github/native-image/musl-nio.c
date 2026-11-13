#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

_Static_assert(sizeof(off_t) == 8, "NIO compatibility requires 64-bit file offsets");

extern void *__real_dlsym(void *handle, const char *name);

/*
 * JDK UnixNativeDispatcher discovers its secure-directory functions with dlsym.
 * Fully static musl cannot look them up dynamically. Return the real, statically
 * linked POSIX functions, including JDK's LFS64 names (musl uses 64-bit off_t).
 * Other lookups retain musl's original behavior. No path validation is relaxed.
 */
void *__wrap_dlsym(void *handle, const char *name) {
    if (handle == RTLD_DEFAULT) {
        if (!strcmp(name, "openat64") || !strcmp(name, "openat")) return (void *)openat;
        if (!strcmp(name, "fstatat64") || !strcmp(name, "fstatat")) return (void *)fstatat;
        if (!strcmp(name, "unlinkat")) return (void *)unlinkat;
        if (!strcmp(name, "renameat")) return (void *)renameat;
        if (!strcmp(name, "fdopendir")) return (void *)fdopendir;
    }
    return __real_dlsym(handle, name);
}
