#include <sys/event.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/proc_info.h>
#include <libproc.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static int owner_matches(pid_t pid, unsigned long long milliseconds) {
    struct proc_bsdinfo info;
    int bytes = proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, sizeof(info));
    return bytes == sizeof(info) &&
        info.pbi_start_tvsec * 1000ULL + info.pbi_start_tvusec / 1000ULL == milliseconds;
}

/* completionはclient群の回収証拠だけで、daemon操作の成功を表さない。 */
static int complete(const char *path, const char *digest) {
    size_t length = strlen(path) + 9;
    char *pending = malloc(length);
    if (!pending) return -1;
    snprintf(pending, length, "%s.pending", path);
    int file = open(pending, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    if (file < 0) { free(pending); return -1; }
    char body[66];
    snprintf(body, sizeof(body), "%s\n", digest);
    int result = write(file, body, 65) == 65 && fsync(file) == 0 ? 0 : -1;
    if (close(file) < 0) result = -1;
    if (result == 0 && renamex_np(pending, path, RENAME_EXCL) < 0) result = -1;
    if (result < 0) unlink(pending);
    free(pending);
    return result;
}

int main(int argc, char **argv) {
    if (argc < 7 || strcmp(argv[1], "--completion") || argv[2][0] != '/' ||
        strcmp(argv[3], "--intent-sha") || strlen(argv[4]) != 64) return 1;
    const char *completion = argv[2], *digest = argv[4];
    for (int i = 0; i < 64; i++) if (!strchr("0123456789abcdef", digest[i])) return 1;
    int next = 5;
    pid_t owner = 0;
    unsigned long long started = 0;
    if (next < argc && !strcmp(argv[next], "--owner")) {
        if (argc <= next + 3) return 1;
        char *end;
        long parsed = strtol(argv[next + 1], &end, 10);
        if (*end || parsed <= 1 || parsed > 2147483647) return 1;
        owner = (pid_t)parsed;
        started = strtoull(argv[next + 2], &end, 10);
        if (*end || !started || !owner_matches(owner, started)) return 1;
        next += 3;
    }
    if (argc <= next + 1 || strcmp(argv[next], "--") || argv[next + 1][0] != '/') return 1;
    next++;
    pid_t parent = getppid();
    int queue = kqueue();
    if (queue < 0 || parent <= 1) return 1;
    struct kevent changes[4];
    EV_SET(&changes[0], (uintptr_t)parent, EVFILT_PROC, EV_ADD | EV_ENABLE, NOTE_EXIT, 0, NULL);
    EV_SET(&changes[1], SIGTERM, EVFILT_SIGNAL, EV_ADD | EV_ENABLE, 0, 0, NULL);
    EV_SET(&changes[2], SIGINT, EVFILT_SIGNAL, EV_ADD | EV_ENABLE, 0, 0, NULL);
    if (owner) EV_SET(&changes[3], (uintptr_t)owner, EVFILT_PROC, EV_ADD | EV_ENABLE, NOTE_EXIT, 0, NULL);
    if (kevent(queue, changes, owner ? 4 : 3, NULL, 0, NULL) < 0 || getppid() != parent ||
        (owner && !owner_matches(owner, started))) return 1;
    signal(SIGTERM, SIG_IGN);
    signal(SIGINT, SIG_IGN);
    pid_t child = fork();
    if (child < 0) return 1;
    if (child == 0) {
        close(queue);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        if (setpgid(0, 0) < 0) _exit(126);
        execv(argv[next], &argv[next]);
        _exit(127);
    }
    int failed = setpgid(child, child) < 0 && errno != EACCES && errno != ESRCH;
    int observed = -1;
    if (!failed) {
        struct kevent child_event;
        EV_SET(&child_event, (uintptr_t)child, EVFILT_PROC, EV_ADD | EV_ENABLE, NOTE_EXIT, 0, NULL);
        observed = kevent(queue, &child_event, 1, NULL, 0, NULL);
        if (observed < 0 && errno == ESRCH) observed = 0;
        else if (observed >= 0) {
            struct kevent event;
            do { observed = kevent(queue, NULL, 0, &event, 1, NULL); } while (observed < 0 && errno == EINTR);
        }
    }
    /* 未reapのleaderでPGID再利用を防ぎ、最後のsignalを送る。reap後は観測だけ。 */
    kill(-child, SIGKILL);
    if (failed) kill(child, SIGKILL);
    int status = 0, reaped = 0;
    struct timespec pause = {0, 10000000};
    for (int i = 0; i < 200; i++) {
        if (!reaped) {
            pid_t done = waitpid(child, &status, WNOHANG);
            if (done == child) reaped = 1;
            else if (done < 0 && errno != EINTR) break;
        }
        if (reaped && kill(-child, 0) < 0 && errno == ESRCH) {
            close(queue);
            if (failed || observed < 0 || complete(completion, digest) < 0) return 1;
            return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
        }
        nanosleep(&pause, NULL);
    }
    fprintf(stderr, "client process群の有限回収を確認できません\n");
    close(queue);
    return 1;
}
