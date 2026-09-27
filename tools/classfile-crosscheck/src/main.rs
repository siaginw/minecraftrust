//! Independent *tooling* cross-check, never an authority or qualification grant.
#![forbid(unsafe_code)]

use cafebabe::attributes::*;
use cafebabe::bytecode::{ByteCode, Opcode, PrimitiveArrayType};
use cafebabe::constant_pool::*;
use cafebabe::descriptors::FieldType;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, env, fs, path::Path};

type Result<T> = std::result::Result<T, String>;

fn sorted(mut values: Vec<Value>) -> Value {
    values.sort_by_key(Value::to_string);
    json!(values)
}

fn signature(attrs: &[AttributeInfo<'_>]) -> Value {
    attrs
        .iter()
        .find_map(|a| match &a.data {
            AttributeData::Signature(x) => Some(json!(x)),
            _ => None,
        })
        .unwrap_or(Value::Null)
}

fn access(raw: u16, attrs: &[AttributeInfo<'_>]) -> u32 {
    attrs.iter().fold(raw as u32, |v, a| {
        v | match a.data {
            AttributeData::Synthetic => 0x1000,
            AttributeData::Deprecated => 0x20000,
            _ => 0,
        }
    })
}

fn literal(v: &LiteralConstant<'_>) -> Result<Value> {
    Ok(match v {
        LiteralConstant::Integer(x) => json!(["int", x]),
        LiteralConstant::Long(x) => json!(["long", x.to_string()]),
        LiteralConstant::Float(x) => json!(["float", format!("{:08x}", x.to_bits())]),
        LiteralConstant::Double(x) => json!(["double", format!("{:016x}", x.to_bits())]),
        LiteralConstant::String(x) => json!(["string", x]),
        LiteralConstant::StringBytes(_) => return Err("unpaired-surrogate string cannot be represented losslessly by cafebabe UTF-8; reject, do not replace".into()),
    })
}

fn handle(h: &MethodHandle<'_>) -> Value {
    let kind = match h.kind {
        ReferenceKind::GetField => 1,
        ReferenceKind::GetStatic => 2,
        ReferenceKind::PutField => 3,
        ReferenceKind::PutStatic => 4,
        ReferenceKind::InvokeVirtual => 5,
        ReferenceKind::InvokeStatic => 6,
        ReferenceKind::InvokeSpecial => 7,
        ReferenceKind::NewInvokeSpecial => 8,
        ReferenceKind::InvokeInterface => 9,
    };
    json!([
        "handle",
        kind,
        h.class_name,
        h.member_ref.name,
        h.member_ref.descriptor,
        matches!(h.member_kind, MemberKind::InterfaceMethod)
    ])
}

fn class_literal(s: &str) -> Value {
    json!([
        "type",
        if s.starts_with('[') {
            s.to_string()
        } else {
            format!("L{s};")
        }
    ])
}

fn loadable(v: &Loadable<'_>) -> Result<Value> {
    match v {
        Loadable::LiteralConstant(x) => literal(x),
        Loadable::ClassInfo(x) => Ok(class_literal(x)),
        Loadable::MethodHandle(x) => Ok(handle(x)),
        Loadable::MethodType(x) => Ok(json!(["type", x])),
        Loadable::Dynamic(_) => Err("CONSTANT_Dynamic outside Java-8 cross-check scope".into()),
    }
}

fn bootstrap_arg(v: &BootstrapArgument<'_>) -> Result<Value> {
    match v {
        BootstrapArgument::LiteralConstant(x) => literal(x),
        BootstrapArgument::ClassInfo(x) => Ok(class_literal(x)),
        BootstrapArgument::MethodHandle(x) => Ok(handle(x)),
        BootstrapArgument::MethodType(x) => Ok(json!(["type", x])),
    }
}

fn annotation_value(v: &AnnotationElementValue<'_>) -> Result<Value> {
    use AnnotationElementValue::*;
    Ok(match v {
        ByteConstant(x) => json!(["byte", x]),
        CharConstant(x) => json!(["char", x]),
        DoubleConstant(x) => json!(["double", format!("{:016x}", x.to_bits())]),
        FloatConstant(x) => json!(["float", format!("{:08x}", x.to_bits())]),
        IntConstant(x) => json!(["int", x]),
        LongConstant(x) => json!(["long", x.to_string()]),
        ShortConstant(x) => json!(["short", x]),
        BooleanConstant(x) => json!(["boolean", *x != 0]),
        StringConstant(x) => json!(["string", x]),
        EnumConstant {
            type_name,
            const_name,
        } => json!(["enum", type_name.to_string(), const_name]),
        ClassLiteral { class_name } => json!(["type", class_name]),
        AnnotationValue(x) => json!(["annotation", annotation(x)?]),
        ArrayValue(xs) => json!([
            "array",
            xs.iter()
                .map(annotation_value)
                .collect::<Result<Vec<_>>>()?
        ]),
    })
}

fn annotation(a: &Annotation<'_>) -> Result<Value> {
    // Element order is preserved; no unsupported assumptions about reflection order.
    Ok(json!([
        a.type_descriptor.to_string(),
        a.elements
            .iter()
            .map(|e| Ok(json!([e.name, annotation_value(&e.value)?])))
            .collect::<Result<Vec<_>>>()?
    ]))
}

fn annotations(attrs: &[AttributeInfo<'_>]) -> Result<Value> {
    let mut result = vec![];
    for a in attrs {
        let (visible, items) = match &a.data {
            AttributeData::RuntimeVisibleAnnotations(xs) => (true, xs),
            AttributeData::RuntimeInvisibleAnnotations(xs) => (false, xs),
            _ => continue,
        };
        for x in items {
            result.push(json!([visible, annotation(x)?]));
        }
    }
    Ok(sorted(result))
}

fn type_annotations(
    attrs: &[AttributeInfo<'_>],
    raw: &RawExtras,
    scope: &str,
    code: Option<&CodeData<'_>>,
) -> Result<Value> {
    let mut result = vec![];
    for a in attrs {
        let (visible, items) = match &a.data {
            AttributeData::RuntimeVisibleTypeAnnotations(xs) => (true, xs),
            AttributeData::RuntimeInvisibleTypeAnnotations(xs) => (false, xs),
            _ => continue,
        };
        let tags = raw
            .type_tags
            .get(&format!("{scope}/{}", a.name))
            .ok_or("missing independent raw type-annotation tags")?;
        if tags.len() != items.len() {
            return Err("raw/cafebabe type annotation count disagreement".into());
        }
        for (x, tag) in items.iter().zip(tags) {
            let path: Vec<Value> = x
                .target_path
                .iter()
                .map(|p| {
                    json!([
                        match p.path_kind {
                            TypeAnnotationTargetPathKind::DeeperArray => 0,
                            TypeAnnotationTargetPathKind::DeeperNested => 1,
                            TypeAnnotationTargetPathKind::WildcardTypeArgument => 2,
                            TypeAnnotationTargetPathKind::TypeArgument => 3,
                        },
                        p.argument_index
                    ])
                })
                .collect();
            let offset = |pc: usize| -> Result<usize> {
                let c = code.ok_or("code type target outside Code")?;
                target(
                    c.bytecode.as_ref().ok_or("missing bytecode")?,
                    c.code.len(),
                    pc,
                )
            };
            let target = match &x.target_type {
                TypeAnnotationTarget::TypeParameter { index } => json!([tag, index]),
                TypeAnnotationTarget::Supertype { index } => json!([tag, index]),
                TypeAnnotationTarget::TypeParameterBound {
                    type_parameter_index,
                    bound_index,
                } => json!([tag, type_parameter_index, bound_index]),
                TypeAnnotationTarget::Empty => json!([tag]),
                TypeAnnotationTarget::FormalParameter { index } => json!([tag, index]),
                TypeAnnotationTarget::Throws { index } => json!([tag, index]),
                TypeAnnotationTarget::LocalVar(xs) => json!([
                    tag,
                    xs.iter()
                        .map(|r| Ok(json!([
                            offset(r.start_pc as usize)?,
                            offset(r.start_pc as usize + r.length as usize)?,
                            r.index
                        ])))
                        .collect::<Result<Vec<_>>>()?
                ]),
                TypeAnnotationTarget::Catch {
                    exception_table_index,
                } => json!([tag, exception_table_index]),
                TypeAnnotationTarget::Offset { offset: pc } => json!([tag, offset(*pc as usize)?]),
                TypeAnnotationTarget::TypeArgument {
                    offset: pc,
                    type_argument_index,
                } => json!([tag, offset(*pc as usize)?, type_argument_index]),
            };
            result.push(json!([visible, target, path, annotation(&x.annotation)?]));
        }
    }
    Ok(sorted(result))
}

fn target(bc: &ByteCode<'_>, len: usize, offset: usize) -> Result<usize> {
    if offset == len {
        return Ok(bc.opcodes.len());
    }
    bc.get_opcode_index(offset)
        .ok_or_else(|| format!("target {offset} is not an instruction boundary"))
}

fn relative(bc: &ByteCode<'_>, len: usize, source: usize, delta: i32) -> Result<usize> {
    let offset =
        usize::try_from(source as i64 + delta as i64).map_err(|_| "negative branch target")?;
    if offset == len {
        return Err("branch targets end of code".into());
    }
    target(bc, len, offset)
}

fn object_type(t: &ObjectArrayType<'_>) -> String {
    match t {
        ObjectArrayType::ArrayType(x) => x.to_string(),
        ObjectArrayType::BinaryName(x) => x.to_string(),
    }
}

fn instruction(
    op: &Opcode<'_>,
    offset: usize,
    code: &CodeData<'_>,
    cp_tags: &[u8],
    bootstraps: &[BootstrapMethodEntry<'_>],
) -> Result<Value> {
    use Opcode::*;
    let bc = code.bytecode.as_ref().ok_or("bytecode parsing disabled")?;
    let len = code.code.len();
    let jump = |d| relative(bc, len, offset, d);
    let raw = code.code[offset];
    Ok(match op {
        Bipush(x) => json!([16, x]),
        Sipush(x) => json!([17, x]),
        Ldc(x) | LdcW(x) | Ldc2W(x) => json!([18, loadable(x)?]),
        Iload(x) => json!([21, x]),
        Lload(x) => json!([22, x]),
        Fload(x) => json!([23, x]),
        Dload(x) => json!([24, x]),
        Aload(x) => json!([25, x]),
        Istore(x) => json!([54, x]),
        Lstore(x) => json!([55, x]),
        Fstore(x) => json!([56, x]),
        Dstore(x) => json!([57, x]),
        Astore(x) => json!([58, x]),
        Ret(x) => json!([169, x]),
        Iinc(x, y) => json!([132, x, y]),
        Ifeq(x) | Ifne(x) | Iflt(x) | Ifge(x) | Ifgt(x) | Ifle(x) | IfIcmpeq(x) | IfIcmpne(x)
        | IfIcmplt(x) | IfIcmpge(x) | IfIcmpgt(x) | IfIcmple(x) | IfAcmpeq(x) | IfAcmpne(x)
        | Ifnull(x) | Ifnonnull(x) => json!([raw, jump(*x)?]),
        Goto(x) => json!([167, jump(*x)?]),
        Jsr(x) => json!([168, jump(*x)?]),
        Tableswitch(x) => json!([
            170,
            x.low,
            x.high,
            jump(x.default)?,
            x.jumps
                .iter()
                .map(|d| jump(*d))
                .collect::<Result<Vec<_>>>()?
        ]),
        Lookupswitch(x) => json!([
            171,
            jump(x.default)?,
            x.match_offsets
                .iter()
                .map(|(k, d)| Ok(json!([k, jump(*d)?])))
                .collect::<Result<Vec<_>>>()?
        ]),
        Getstatic(m) | Putstatic(m) | Getfield(m) | Putfield(m) => json!([
            raw,
            m.class_name,
            m.name_and_type.name,
            m.name_and_type.descriptor
        ]),
        Invokevirtual(m) | Invokespecial(m) | Invokestatic(m) | Invokeinterface(m, _) => {
            let index = u16::from_be_bytes([code.code[offset + 1], code.code[offset + 2]]) as usize;
            let tag = *cp_tags.get(index).ok_or("invalid CP member index")?;
            if tag != 10 && tag != 11 {
                return Err("invocation references non-method CP tag".into());
            }
            json!([
                raw,
                m.class_name,
                m.name_and_type.name,
                m.name_and_type.descriptor,
                tag == 11
            ])
        }
        Invokedynamic(x) => {
            let b = bootstraps
                .get(x.attr_index as usize)
                .ok_or("missing bootstrap method")?;
            json!([
                186,
                x.name_and_type.name,
                x.name_and_type.descriptor,
                handle(&b.method),
                b.arguments
                    .iter()
                    .map(bootstrap_arg)
                    .collect::<Result<Vec<_>>>()?
            ])
        }
        New(x) => json!([187, x]),
        Anewarray(x) => json!([189, object_type(x)]),
        Checkcast(x) => json!([192, object_type(x)]),
        Instanceof(x) => json!([193, object_type(x)]),
        Multianewarray(x, d) => json!([197, object_type(x), d]),
        Newarray(t) => json!([
            188,
            match t {
                PrimitiveArrayType::Boolean => 4,
                PrimitiveArrayType::Char => 5,
                PrimitiveArrayType::Float => 6,
                PrimitiveArrayType::Double => 7,
                PrimitiveArrayType::Byte => 8,
                PrimitiveArrayType::Short => 9,
                PrimitiveArrayType::Int => 10,
                PrimitiveArrayType::Long => 11,
            }
        ]),
        Breakpoint | Impdep1 | Impdep2 => {
            return Err("reserved opcode outside classfile contract".into())
        }
        _ => json!([raw]), // cafebabe's remaining variants are operand-free JVM opcodes.
    })
}

fn verification(t: &VerificationType<'_>, bc: &ByteCode<'_>, len: usize) -> Result<Value> {
    use VerificationType::*;
    Ok(match t {
        Top => json!(0),
        Integer => json!(1),
        Float => json!(2),
        Double => json!(3),
        Long => json!(4),
        Null => json!(5),
        UninitializedThis => json!(6),
        Object { class_name } => json!(class_name),
        Uninitialized { code_offset } => {
            json!(["uninitialized", target(bc, len, *code_offset as usize)?])
        }
    })
}

fn initial_locals(class_name: &str, method: &cafebabe::MethodInfo<'_>) -> Vec<Value> {
    let mut locals = vec![];
    if method.access_flags.bits() & 8 == 0 {
        locals.push(if method.name == "<init>" {
            json!(6)
        } else {
            json!(class_name)
        });
    }
    for p in &method.descriptor.parameters {
        locals.push(if p.dimensions > 0 {
            json!(p.to_string())
        } else {
            match &p.field_type {
                FieldType::Object(n) => json!(n.to_string()),
                FieldType::Float => json!(2),
                FieldType::Double => json!(3),
                FieldType::Long => json!(4),
                _ => json!(1),
            }
        });
    }
    locals
}

fn frames(
    code: &CodeData<'_>,
    class_name: &str,
    method: &cafebabe::MethodInfo<'_>,
) -> Result<Value> {
    let bc = code.bytecode.as_ref().ok_or("missing bytecode")?;
    let mut locals = initial_locals(class_name, method);
    let mut previous = -1_i64;
    let mut result = vec![];
    for attr in &code.attributes {
        if let AttributeData::StackMapTable(entries) = &attr.data {
            for entry in entries {
                let (delta, stack) = match entry {
                    StackMapEntry::Same { offset_delta } => (*offset_delta, vec![]),
                    StackMapEntry::SameLocals1StackItem {
                        offset_delta,
                        stack,
                    } => (
                        *offset_delta,
                        vec![verification(stack, bc, code.code.len())?],
                    ),
                    StackMapEntry::Chop {
                        offset_delta,
                        chop_count,
                    } => {
                        let n = locals
                            .len()
                            .checked_sub(*chop_count as usize)
                            .ok_or("invalid stackmap chop")?;
                        locals.truncate(n);
                        (*offset_delta, vec![])
                    }
                    StackMapEntry::Append {
                        offset_delta,
                        locals: extra,
                    } => {
                        locals.extend(
                            extra
                                .iter()
                                .map(|x| verification(x, bc, code.code.len()))
                                .collect::<Result<Vec<_>>>()?,
                        );
                        (*offset_delta, vec![])
                    }
                    StackMapEntry::FullFrame {
                        offset_delta,
                        locals: replacement,
                        stack,
                    } => {
                        locals = replacement
                            .iter()
                            .map(|x| verification(x, bc, code.code.len()))
                            .collect::<Result<Vec<_>>>()?;
                        (
                            *offset_delta,
                            stack
                                .iter()
                                .map(|x| verification(x, bc, code.code.len()))
                                .collect::<Result<Vec<_>>>()?,
                        )
                    }
                };
                previous += i64::from(delta) + 1;
                result.push(json!([
                    target(bc, code.code.len(), previous as usize)?,
                    locals,
                    stack
                ]));
            }
        }
    }
    Ok(json!(result))
}

fn code_facts(
    code: &CodeData<'_>,
    class_name: &str,
    method: &cafebabe::MethodInfo<'_>,
    raw: &RawExtras,
    scope: &str,
    bootstraps: &[BootstrapMethodEntry<'_>],
) -> Result<Value> {
    let bc = code.bytecode.as_ref().ok_or("missing bytecode")?;
    let mut lines = vec![];
    let mut locals = vec![];
    let mut local_types = vec![];
    for a in &code.attributes {
        match &a.data {
            AttributeData::LineNumberTable(xs) => {
                for x in xs {
                    lines.push(json!([
                        target(bc, code.code.len(), x.start_pc as usize)?,
                        x.line_number
                    ]));
                }
            }
            AttributeData::LocalVariableTable(xs) => {
                for x in xs {
                    locals.push(json!([
                        target(bc, code.code.len(), x.start_pc as usize)?,
                        target(bc, code.code.len(), x.start_pc as usize + x.length as usize)?,
                        x.name,
                        x.descriptor.to_string(),
                        x.index
                    ]));
                }
            }
            AttributeData::LocalVariableTypeTable(xs) => {
                for x in xs {
                    local_types.push(json!([
                        target(bc, code.code.len(), x.start_pc as usize)?,
                        target(bc, code.code.len(), x.start_pc as usize + x.length as usize)?,
                        x.name,
                        x.signature,
                        x.index
                    ]));
                }
            }
            _ => (),
        }
    }
    let handlers = code
        .exception_table
        .iter()
        .map(|x| {
            Ok(json!([
                target(bc, code.code.len(), x.start_pc as usize)?,
                target(bc, code.code.len(), x.end_pc as usize)?,
                target(bc, code.code.len(), x.handler_pc as usize)?,
                x.catch_type
            ]))
        })
        .collect::<Result<Vec<_>>>()?;
    Ok(
        json!({"max_stack":code.max_stack,"max_locals":code.max_locals,
        "instructions":bc.opcodes.iter().map(|(offset,op)|instruction(op,*offset,code,&raw.cp_tags,bootstraps)).collect::<Result<Vec<_>>>()?,
        "handlers":handlers,"frames":frames(code,class_name,method)?,"lines":sorted(lines),"locals":sorted(locals),
        "local_types":sorted(local_types),"type_annotations":type_annotations(&code.attributes,raw,&format!("{scope}/code"),Some(code))?}),
    )
}

fn check_attributes(
    attrs: &[AttributeInfo<'_>],
    inventory: &mut BTreeMap<String, usize>,
) -> Result<()> {
    for a in attrs {
        *inventory.entry(a.name.to_string()).or_default() += 1;
        match &a.data {
            AttributeData::Other(_) => return Err(format!("unsupported attribute {}", a.name)),
            AttributeData::Module(_)
            | AttributeData::ModulePackages(_)
            | AttributeData::ModuleMainClass(_)
            | AttributeData::NestHost(_)
            | AttributeData::NestMembers(_)
            | AttributeData::PermittedSubclasses(_)
            | AttributeData::Record(_) => return Err(format!("post-Java-8 attribute {}", a.name)),
            AttributeData::Code(c) => check_attributes(&c.attributes, inventory)?,
            _ => (),
        }
    }
    Ok(())
}

// Only CP tags are read here. Every symbolic resolution and bytecode parse comes
// from cafebabe, independently of ASM. This recovers a bit its MemberRef erases.
fn cp_tags(bytes: &[u8]) -> Result<Vec<u8>> {
    let u16_at = |p: usize| -> Result<usize> {
        let b = bytes.get(p..p + 2).ok_or("truncated CP")?;
        Ok(u16::from_be_bytes([b[0], b[1]]) as usize)
    };
    let count = u16_at(8)?;
    let mut tags = vec![0; count];
    let mut cursor = 10;
    let mut i = 1;
    while i < count {
        let tag = *bytes.get(cursor).ok_or("truncated CP tag")?;
        tags[i] = tag;
        cursor += 1;
        let size = match tag {
            1 => 2 + u16_at(cursor)?,
            3 | 4 | 9 | 10 | 11 | 12 | 18 => 4,
            5 | 6 => 8,
            7 | 8 | 16 => 2,
            15 => 3,
            _ => return Err(format!("unsupported CP tag {tag}")),
        };
        cursor = cursor.checked_add(size).ok_or("CP overflow")?;
        if cursor > bytes.len() {
            return Err("truncated CP body".into());
        }
        if tag == 5 || tag == 6 {
            i += 1;
        }
        i += 1;
    }
    Ok(tags)
}

#[derive(Default)]
struct RawExtras {
    cp_tags: Vec<u8>,
    class_access: u16,
    field_access: Vec<u16>,
    method_access: Vec<u16>,
    type_tags: BTreeMap<String, Vec<u8>>,
}

struct Cursor<'a> {
    bytes: &'a [u8],
    p: usize,
}
impl Cursor<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8]> {
        let end = self.p.checked_add(n).ok_or("raw length overflow")?;
        let s = self.bytes.get(self.p..end).ok_or("truncated raw class")?;
        self.p = end;
        Ok(s)
    }
    fn u1(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u2(&mut self) -> Result<u16> {
        let x = self.take(2)?;
        Ok(u16::from_be_bytes([x[0], x[1]]))
    }
    fn u4(&mut self) -> Result<u32> {
        let x = self.take(4)?;
        Ok(u32::from_be_bytes([x[0], x[1], x[2], x[3]]))
    }
    fn skip_annotation(&mut self) -> Result<()> {
        self.u2()?;
        let n = self.u2()?;
        for _ in 0..n {
            self.u2()?;
            self.skip_element()?;
        }
        Ok(())
    }
    fn skip_element(&mut self) -> Result<()> {
        match self.u1()? {
            b'B' | b'C' | b'D' | b'F' | b'I' | b'J' | b'S' | b'Z' | b's' | b'c' => {
                self.u2()?;
            }
            b'e' => {
                self.take(4)?;
            }
            b'@' => self.skip_annotation()?,
            b'[' => {
                let n = self.u2()?;
                for _ in 0..n {
                    self.skip_element()?;
                }
            }
            _ => return Err("unknown annotation element tag".into()),
        }
        Ok(())
    }
    fn attrs(&mut self, names: &[String], scope: &str, raw: &mut RawExtras) -> Result<()> {
        let count = self.u2()?;
        for _ in 0..count {
            let index = self.u2()? as usize;
            let name = names
                .get(index)
                .ok_or("raw attribute CP name out of range")?;
            let len = self.u4()? as usize;
            let bytes = self.take(len)?;
            let mut a = Cursor { bytes, p: 0 };
            if name == "Code" {
                a.take(4)?;
                let len = a.u4()? as usize;
                a.take(len)?;
                let n = a.u2()? as usize;
                a.take(n * 8)?;
                a.attrs(names, &format!("{scope}/code"), raw)?;
            } else if name == "RuntimeVisibleTypeAnnotations"
                || name == "RuntimeInvisibleTypeAnnotations"
            {
                let n = a.u2()?;
                let mut tags = vec![];
                for _ in 0..n {
                    let tag = a.u1()?;
                    tags.push(tag);
                    match tag {
                        0x00 | 0x01 | 0x16 => {
                            a.take(1)?;
                        }
                        0x10 | 0x11 | 0x12 | 0x17 | 0x42 | 0x43..=0x46 => {
                            a.take(2)?;
                        }
                        0x13..=0x15 => (),
                        0x40 | 0x41 => {
                            let n = a.u2()? as usize;
                            a.take(n * 6)?;
                        }
                        0x47..=0x4b => {
                            a.take(3)?;
                        }
                        _ => return Err(format!("unknown type target {tag}")),
                    }
                    let n = a.u1()? as usize;
                    a.take(n * 2)?;
                    a.skip_annotation()?;
                }
                if raw
                    .type_tags
                    .insert(format!("{scope}/{name}"), tags)
                    .is_some()
                {
                    return Err("duplicate type annotation attribute".into());
                }
            } else {
                continue;
            }
            if a.p != a.bytes.len() {
                return Err(format!("raw attribute {name} not fully consumed"));
            }
        }
        Ok(())
    }
}

fn raw_extras(bytes: &[u8]) -> Result<RawExtras> {
    let tags = cp_tags(bytes)?;
    let mut c = Cursor { bytes, p: 10 };
    let mut names = vec![String::new(); tags.len()];
    let mut i = 1;
    while i < tags.len() {
        let tag = c.u1()?;
        match tag {
            1 => {
                let n = c.u2()? as usize;
                let b = c.take(n)?; // Attribute names are ASCII. Other CP strings need not be decoded here.
                if b.is_ascii() {
                    names[i] = String::from_utf8(b.to_vec()).map_err(|e| e.to_string())?;
                }
            }
            3 | 4 | 9 | 10 | 11 | 12 | 18 => {
                c.take(4)?;
            }
            5 | 6 => {
                c.take(8)?;
                i += 1;
            }
            7 | 8 | 16 => {
                c.take(2)?;
            }
            15 => {
                c.take(3)?;
            }
            _ => return Err("bad raw CP tag".into()),
        }
        i += 1;
    }
    let mut raw = RawExtras {
        cp_tags: tags,
        class_access: c.u2()?,
        ..RawExtras::default()
    };
    c.take(4)?;
    let n = c.u2()? as usize;
    c.take(n * 2)?;
    for kind in ["field", "method"] {
        let n = c.u2()?;
        for i in 0..n {
            let flags = c.u2()?;
            if kind == "field" {
                raw.field_access.push(flags);
            } else {
                raw.method_access.push(flags);
            }
            c.take(4)?;
            c.attrs(&names, &format!("{kind}/{i}"), &mut raw)?;
        }
    }
    c.attrs(&names, "class", &mut raw)?;
    if c.p != bytes.len() {
        return Err("trailing raw bytes".into());
    }
    Ok(raw)
}

fn facts(bytes: &[u8]) -> Result<(Value, Value)> {
    let c = cafebabe::parse_class(bytes).map_err(|e| format!("cafebabe: {e}"))?;
    if c.major_version > 52 {
        return Err(format!(
            "class version {} outside Java-8 scope",
            c.major_version
        ));
    }
    let raw = raw_extras(bytes)?;
    let mut inventory = BTreeMap::new();
    check_attributes(&c.attributes, &mut inventory)?;
    for f in &c.fields {
        check_attributes(&f.attributes, &mut inventory)?;
    }
    for m in &c.methods {
        check_attributes(&m.attributes, &mut inventory)?;
    }
    let bootstraps = c
        .attributes
        .iter()
        .find_map(|a| {
            if let AttributeData::BootstrapMethods(x) = &a.data {
                Some(x.as_slice())
            } else {
                None
            }
        })
        .unwrap_or(&[]);
    let mut fields = vec![];
    for (i, f) in c.fields.iter().enumerate() {
        let constant = f
            .attributes
            .iter()
            .find_map(|a| {
                if let AttributeData::ConstantValue(v) = &a.data {
                    Some(literal(v))
                } else {
                    None
                }
            })
            .transpose()?
            .unwrap_or(Value::Null);
        fields.push(json!({"name":f.name,"descriptor":f.descriptor.to_string(),"access":access(raw.field_access[i],&f.attributes),"signature":signature(&f.attributes),"constant":constant,"annotations":annotations(&f.attributes)?,"type_annotations":type_annotations(&f.attributes,&raw,&format!("field/{i}"),None)?}));
    }
    let mut methods = vec![];
    for (i, m) in c.methods.iter().enumerate() {
        let scope = format!("method/{i}");
        let mut exceptions = vec![];
        let mut parameters = Value::Null;
        let mut parameter_annotation_counts = [Value::Null, Value::Null];
        let mut parameter_annotations = vec![];
        let mut default = Value::Null;
        let mut code = Value::Null;
        for a in &m.attributes {
            match &a.data {
                AttributeData::Exceptions(xs) => exceptions = xs.iter().map(|x| json!(x)).collect(),
                AttributeData::MethodParameters(xs) => {
                    parameters = json!(xs
                        .iter()
                        .map(|x| json!([x.name, x.access_flags.bits()]))
                        .collect::<Vec<_>>())
                }
                AttributeData::AnnotationDefault(x) => default = annotation_value(x)?,
                AttributeData::Code(x) => {
                    code = code_facts(x, &c.this_class, m, &raw, &scope, bootstraps)?
                }
                AttributeData::RuntimeVisibleParameterAnnotations(xs)
                | AttributeData::RuntimeInvisibleParameterAnnotations(xs) => {
                    let visible =
                        matches!(a.data, AttributeData::RuntimeVisibleParameterAnnotations(_));
                    parameter_annotation_counts[if visible { 0 } else { 1 }] = json!(xs.len());
                    for (i, p) in xs.iter().enumerate() {
                        for an in &p.annotations {
                            parameter_annotations.push(json!([visible, i, annotation(an)?]));
                        }
                    }
                }
                _ => (),
            }
        }
        let method_parameter_count = parameters.as_array().map(Vec::len);
        methods.push(json!({"name":m.name,"descriptor":m.descriptor.to_string(),"access":access(raw.method_access[i],&m.attributes),"signature":signature(&m.attributes),"exceptions":exceptions,"parameters":parameters,"method_parameter_count":method_parameter_count,"parameter_annotation_counts":parameter_annotation_counts,"parameter_annotations":sorted(parameter_annotations),"annotation_default":default,"annotations":annotations(&m.attributes)?,"type_annotations":type_annotations(&m.attributes,&raw,&scope,None)?,"code":code}));
    }
    let mut source = Value::Null;
    let mut debug = Value::Null;
    let mut enclosing = Value::Null;
    let mut inner = vec![];
    for a in &c.attributes {
        match &a.data {
            AttributeData::SourceFile(x) => source = json!(x),
            AttributeData::SourceDebugExtension(x) => debug = json!(x),
            AttributeData::EnclosingMethod { class_name, method } => {
                enclosing = json!([
                    class_name,
                    method.as_ref().map(|m| m.name.as_ref()),
                    method.as_ref().map(|m| m.descriptor.as_ref())
                ])
            }
            AttributeData::InnerClasses(xs) => {
                for x in xs {
                    inner.push(json!([
                        x.inner_class_info,
                        x.outer_class_info,
                        x.inner_name,
                        x.access_flags.bits()
                    ]));
                }
            }
            _ => (),
        }
    }
    // Keep declaration order separately. Sorting this comparison does not grant
    // permission to ignore reflection-visible member-order changes in V2.
    let order = json!([
        c.fields
            .iter()
            .map(|f| json!([f.name, f.descriptor.to_string()]))
            .collect::<Vec<_>>(),
        c.methods
            .iter()
            .map(|m| json!([m.name, m.descriptor.to_string()]))
            .collect::<Vec<_>>()
    ]);
    let facts = json!({"schema":"RUSTCRAFT_CLASS_FACTS_V1","version":((c.minor_version as u32)<<16)|c.major_version as u32,"access":access(raw.class_access,&c.attributes),"name":c.this_class.to_string(),"super":c.super_class.as_ref().map(ToString::to_string),"interfaces":c.interfaces.iter().map(ToString::to_string).collect::<Vec<_>>(),"signature":signature(&c.attributes),"source":source,"source_debug":debug,"enclosing":enclosing,"inner_classes":inner,"annotations":annotations(&c.attributes)?,"type_annotations":type_annotations(&c.attributes,&raw,"class",None)?,"fields":sorted(fields),"methods":sorted(methods),"declaration_order":order});
    Ok((facts, json!(inventory)))
}

fn inspect(path: &Path) -> Value {
    let result=fs::read(path).map_err(|e|e.to_string()).and_then(|b| {
        let raw=format!("{:x}",Sha256::digest(&b));
        let (facts,inventory)=facts(&b)?;
        let digest=format!("{:x}",Sha256::digest(serde_json::to_vec(&facts).map_err(|e|e.to_string())?));
        Ok(json!({"status":"PARSED","parser":"cafebabe@0.9.0","raw_sha256":raw,"facts_sha256":digest,"facts":facts,"attribute_inventory":inventory}))
    });
    match result {
        Ok(v) => v,
        Err(e) => json!({"status":"REJECTED","parser":"cafebabe@0.9.0","error":e}),
    }
}

fn main() {
    let paths: Vec<_> = env::args_os().skip(1).collect();
    if paths.is_empty() {
        eprintln!("usage: rustcraft-classfile-crosscheck CLASS... (JSON lines, one per argument)");
        std::process::exit(2);
    }
    let mut failed = false;
    for path in paths {
        let mut v = inspect(Path::new(&path));
        v["path"] = json!(path.to_string_lossy());
        failed |= v["status"] != "PARSED";
        println!("{v}");
    }
    if failed {
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preserves_float_payload_bits() {
        assert_ne!(
            literal(&LiteralConstant::Float(f32::from_bits(0x7fc00001))).unwrap(),
            literal(&LiteralConstant::Float(f32::from_bits(0x7fc00002))).unwrap()
        );
    }
    #[test]
    fn preserves_negative_zero() {
        assert_ne!(
            literal(&LiteralConstant::Double(0.0)).unwrap(),
            literal(&LiteralConstant::Double(-0.0)).unwrap()
        );
    }
    #[test]
    fn truncated_pool_fails() {
        assert!(cp_tags(&[0; 9]).is_err());
    }
    #[test]
    fn unknown_pool_tag_fails() {
        let mut b = vec![0; 11];
        b[9] = 2;
        b[10] = 99;
        assert!(cp_tags(&b).is_err());
    }
    #[test]
    fn malformed_class_rejected() {
        assert!(facts(b"not a class").is_err());
    }
}
