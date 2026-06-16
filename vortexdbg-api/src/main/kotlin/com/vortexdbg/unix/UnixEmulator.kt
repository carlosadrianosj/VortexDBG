package com.vortexdbg.unix

interface UnixEmulator {

    companion object {
        const val EPERM = 1 /* Operation not permitted */
        const val ENOENT = 2 /* No such file or directory */
        const val ESRCH = 3 /* No such process */
        const val EINTR = 4 /* Interrupted system call */
        const val EBADF = 9 /* Bad file descriptor */
        const val EAGAIN = 11 /* Resource temporarily unavailable */
        const val ENOMEM = 12 /* Cannot allocate memory */
        const val EACCES = 13 /* Permission denied */
        const val EFAULT = 14 /* Bad address */
        const val EEXIST = 17 /* File exists */
        const val ENOTDIR = 20 /* Not a directory */
        const val EINVAL = 22 /* Invalid argument */
        const val ENOTTY = 25 /* Inappropriate ioctl for device */
        const val ENOSYS = 38 /* Function not implemented */
        const val ENOATTR = 93 /* Attribute not found */
        const val EOPNOTSUPP = 95 /* Operation not supported on transport endpoint */
        const val EAFNOSUPPORT = 97 /* Address family not supported by protocol family */
        const val EADDRINUSE = 98 /* Address already in use */
        const val ECONNREFUSED = 111 /* Connection refused */
    }
}
