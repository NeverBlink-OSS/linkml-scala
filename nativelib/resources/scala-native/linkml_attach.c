/* Correcting the stack bounds Scala Native records when the library is dlopen'ed.
 *
 * scalanative_GC_init runs from the library constructor and records the address of one of its own
 * locals as the bottom of the calling thread's stack:
 *
 *     volatile word_t dummy = 0;
 *     dummy = (word_t)&dummy;
 *     MutatorThread_init((word_t **)dummy); // approximate stack bottom
 *
 * In a program Scala Native linked itself that runs near the base of the stack this
 * approximation is close enough. Under dlopen it runs wherever the loader happened to be called
 * from, several frames below the code that will actually call in. The collector only scans from
 * that mark down, so frames above it are never scanned and objects they still reference get
 * collected -- ClassCastExceptions on unrelated types, NullPointerExceptions that cannot happen,
 * and segfaults, all only once a workload is big enough to collect at all.
 *
 * Scala Native already knows how to find the real bounds: scalanative_setupCurrentThreadInfo fills
 * in currentThreadInfo using pthread_getattr_np, GetCurrentThreadStackLimits or
 * pthread_get_stackaddr_np, and MutatorThread_getStackBottom prefers that over the approximation.
 * It is just never called for a thread the runtime did not start, so we do it here.
 *
 * See also scala-native#4334.
 */

#include <stdbool.h>
#include <stdint.h>

void scalanative_setupCurrentThreadInfo(void *stackBottom, int32_t stackSize,
                                        bool isMainThread);

/* Big enough to look like a normal thread stack. Only determines the size of the the
 * stack-overflow guard.
 */
#define LINKML_ASSUMED_STACK_SIZE (8 * 1024 * 1024)

/* Call once, from the thread that loaded the library and that will make every call. */
int linkml_init_threads_impl(void) {
    int here;
    scalanative_setupCurrentThreadInfo(&here, LINKML_ASSUMED_STACK_SIZE, false);
    return 0;
}
