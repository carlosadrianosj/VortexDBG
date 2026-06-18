#include <jni.h>
#include <cstdint>
#include <cstring>
#include <unistd.h>

/*
 * libstore.so - a native target with a real linked structure on the heap.
 *   Session { uint32 id; uint32 flags; char name[16]; Session* next; }  (32 bytes on arm64)
 *   g_head -> alice -> bob -> carol -> null
 * store_head_addr() exports &g_head (a pointer-to-pointer) so an MCP client can follow the chain
 * with read_pointer and read real fields with read_typed / read_string / read_memory.
 */

struct Session {
    uint32_t id;
    uint32_t flags;
    char name[16];
    Session *next;
};

static Session *g_head = nullptr;

extern "C" const void *store_head_addr() {
    return reinterpret_cast<const void *>(&g_head);
}

static Session *mk(uint32_t id, uint32_t flags, const char *name, Session *next) {
    Session *s = new Session();
    s->id = id;
    s->flags = flags;
    s->next = next;
    std::memset(s->name, 0, sizeof(s->name));
    std::strncpy(s->name, name, sizeof(s->name) - 1);
    return s;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_store_Store_build(JNIEnv *env, jclass clazz) {
    Session *c = mk(3, 0x0C, "carol", nullptr);
    Session *b = mk(2, 0x02, "bob", c);
    Session *a = mk(1, 0x01, "alice", b);
    g_head = a;
    int n = 0;
    for (Session *p = g_head; p != nullptr; p = p->next) n++;
    return n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_store_Store_rootScore(JNIEnv *env, jclass clazz) {
    int score = 0;
    for (Session *p = g_head; p != nullptr; p = p->next) {
        score += static_cast<int>(p->id ^ p->flags);
    }
    if (access("/system/xbin/su", F_OK) == 0) score += 1000;
    if (access("/system/bin/su", F_OK) == 0) score += 1000;
    return score;
}
