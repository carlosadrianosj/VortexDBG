package com.example.mcpdemo;

/**
 * The demo's mixed Java + native surface, intentionally covering every shape an MCP tool needs:
 *
 *   seal(account, secret) : STATIC native; the derivation runs in libvault.so, which calls BACK
 *                           into Java (Device.salt() / Device.hex()).  -> trace/mock/break, call_static
 *   transform(input)      : INSTANCE native in libvault.so.            -> dvm_call_instance
 *   label / counter       : INSTANCE fields.                           -> dvm_read_field/get_field/set_field
 *   Vault(label)          : constructor.                               -> dvm_new_object
 *
 * The exported native symbols (Java_com_example_mcpdemo_Vault_seal / _transform) also give the
 * native (ARM) MCP tools real targets for list_exports / find_symbol / disassemble_symbol /
 * add_breakpoint_by_symbol.
 */
public class Vault {

    public String label;   // instance field
    public int counter;    // instance field

    public Vault(String label) {
        this.label = label;
        this.counter = 0;
    }

    /** STATIC native: derivation in libvault.so, calls back Device.salt()/hex(). */
    public static native String seal(String account, String secret);

    /** INSTANCE native: transforms input in libvault.so. */
    public native String transform(String input);
}
