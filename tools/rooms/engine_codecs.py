"""What the engine's trigger-volume codecs read, taken from Java source: the Types each codec map registers, every
codec's keys (a class's own and its superclass's), each enum key's accepted values, and the event names. test_rooms.py
checks the logic templates' volumes against it, so a template never writes a key or value the engine would drop.

The engine side comes from the release shared source (builtin/TriggerVolumes, plus the core classes its enums live
in); CommonLib's own Types (ZigRequires, ZigCheckpoint, ZigRunMilestone) from this repository's sources, so a Type
whose source is not here yet is reported missing rather than guessed. Read-only; standard library only.
"""
import os
import re

KEYED = re.compile(r"new\s+KeyedCodec\s*<>\s*\(")
ENUM = re.compile(r"EnumCodec\s*<>\s*\(\s*([\w.]+)\.class(?:\s*,\s*EnumCodec\.EnumStyle\.(\w+))?")
CLASS = re.compile(r"\b(?:class|enum)\s+(\w+)(?:\s*<[^{]*?>)?(?:\s+extends\s+(\w+))?")
REGISTER = re.compile(r"(\w+)\.CODEC\.register\(\s*\"(\w+)\"\s*,\s*(\w+)\.class")
EVENT = re.compile(r"\bregister\(\s*\"([A-Z_]+)\"\s*\)")
SHAPE = re.compile(r"register\(\s*\"(\w+)\"\s*,\s*(\w+)\.class")
REFERENCED = re.compile(r"\b([A-Z]\w*)\.(?:CODEC|codec\s*\()")
VECTOR_CODECS = ("Vector3dUtil.CODEC", "Vector3fUtil.CODEC", "Vector3iUtil.CODEC")


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def balanced(text, start):
    """The text inside the parenthesis opening at `start`, and the index after its close (strings skipped)."""
    depth, i, in_str = 0, start, False
    while i < len(text):
        c = text[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i], i + 1
        i += 1
    return text[start + 1:], len(text)


def split_top(text):
    """`text` split at commas outside parentheses, angle brackets and strings."""
    out, depth, cur, in_str = [], 0, [], False
    for c in text:
        if in_str:
            cur.append(c)
            if c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c in "(<{[":
            depth += 1
        elif c in ")>}]":
            depth -= 1
        elif c == "," and depth == 0:
            out.append("".join(cur))
            cur = []
            continue
        cur.append(c)
    out.append("".join(cur))
    return out


class JavaFile:
    def __init__(self, path):
        self.path = path
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            self.text = strip_comments(f.read())
        self.imports = dict((m.group(2), m.group(1) + "." + m.group(2))
                            for m in re.finditer(r"import\s+([\w.]+)\.(\w+)\s*;", self.text))
        self.package = (re.search(r"package\s+([\w.]+)\s*;", self.text) or [None, ""])[1]
        self.classes = {m.group(1): m.group(2) for m in CLASS.finditer(self.text)}
        self.keys = {}
        for m in KEYED.finditer(self.text):
            inner, _ = balanced(self.text, m.end() - 1)
            args = split_top(inner)
            name = re.match(r'\s*"(\w+)"', args[0])
            if name:
                self.keys[name.group(1)] = args[1].strip() if len(args) > 1 else ""

    def enum_constants(self, name):
        m = re.search(r"\benum\s+%s\b[^{]*\{" % re.escape(name), self.text)
        if not m:
            return None
        body, depth, i = [], 1, m.end()
        while i < len(self.text) and depth:
            c = self.text[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            elif c == ";" and depth == 1:
                break
            body.append(c)
            i += 1
        names = []
        for item in split_top("".join(body)):
            item = re.sub(r"@\w+(\([^)]*\))?", " ", item).strip()
            ident = re.match(r"([A-Za-z_]\w*)", item)
            if ident:
                names.append(ident.group(1))
        return names


def detect_style(names):
    """The engine's EnumStyle.detect (EnumCodec.java): CAMEL_CASE when the names are already written that way."""
    for name in names:
        if len(name) > 1 and name[1].isupper():
            if any(c.isalpha() and c.islower() for c in name[1:]):
                return "CAMEL_CASE"
        else:
            return "CAMEL_CASE"
    return "LEGACY"


def enum_keys(names):
    """The keys an EnumCodec writes and a CAMEL_CASE codec reads (EnumCodec.java, formatCamelCase)."""
    if detect_style(names) == "CAMEL_CASE":
        return list(names)
    return ["".join(p[:1].upper() + p[1:].lower() for p in n.split("_")) for n in names]


class SourceIndex:
    """Every Java file under some roots (each `<module>/src/main/java`), found by package path or simple name."""

    def __init__(self, roots):
        self.by_path = {}
        self.by_name = {}
        self._files = {}
        for root in roots:
            for dirpath, dirnames, filenames in os.walk(root):
                dirnames.sort()
                marker = os.sep + os.path.join("src", "main", "java") + os.sep
                for fn in filenames:
                    if not fn.endswith(".java"):
                        continue
                    full = os.path.join(dirpath, fn)
                    if marker not in full:
                        continue
                    rel = full.split(marker, 1)[1].replace(os.sep, "/")
                    self.by_path.setdefault(rel, full)
                    self.by_name.setdefault(fn[:-5], []).append(full)

    def file(self, path):
        if path not in self._files:
            self._files[path] = JavaFile(path)
        return self._files[path]

    def resolve(self, simple, near):
        """The file declaring class `simple` as `near` (a JavaFile) sees it: nested, imported, same package, or
        the only file of that name."""
        if simple in near.classes:
            return near
        qualified = near.imports.get(simple)
        if qualified:
            p = self.by_path.get(qualified.replace(".", "/") + ".java")
            if p:
                return self.file(p)
        same = os.path.join(os.path.dirname(near.path), simple + ".java")
        if os.path.isfile(same):
            return self.file(same)
        found = self.by_name.get(simple) or []
        return self.file(found[0]) if len(found) == 1 else None

    def class_keys(self, jf, simple, seen=None):
        """{key: codec expression} a class's codec reads: its file's keys and its superclass chain's."""
        seen = seen or set()
        if jf is None or (jf.path, simple) in seen:
            return {}
        seen.add((jf.path, simple))
        keys = dict(jf.keys)
        parent = jf.classes.get(simple)
        if parent:
            keys.update({k: v for k, v in self.class_keys(self.resolve(parent, jf), parent, seen).items()
                         if k not in keys})
        return keys

    def enum_values(self, jf, expr):
        """The values an enum codec in `expr` accepts (None when `expr` is no enum codec)."""
        m = ENUM.search(expr)
        if not m:
            return None
        parts = m.group(1).split(".")
        target = self.resolve(parts[0], jf)
        names = target.enum_constants(parts[-1]) if target else None
        if not names:
            return None
        keys = enum_keys(names)
        if m.group(2) == "LEGACY":
            return {"keys": set(keys), "names": {n.lower() for n in names}}
        return {"keys": set(keys), "names": set()}


class TypeInfo:
    def __init__(self, index, jf, simple):
        self.index = index
        self.file = jf
        self.simple = simple
        self.keys = index.class_keys(jf, simple)


class EngineCodecs:
    """The trigger-volume codecs of a HytaleServer source tree, and CommonLib's own Types beside them."""

    def __init__(self, server_root, zc_root):
        tv = os.path.join(server_root, "builtin", "TriggerVolumes")
        # The plugin, and the core module its enums (block Rotation) and codecs live in.
        self.server = SourceIndex([tv, os.path.join(server_root, "CoreServer")])
        self.zc = SourceIndex([zc_root])
        pkg = "com/hypixel/hytale/builtin/triggervolumes/"
        plugin = self.server.file(self.server.by_path[pkg + "TriggerVolumesPlugin.java"])
        self.types = {"TriggerEffect": {}, "TriggerCondition": {}, "TriggerRule": {}}
        for base, type_id, cls in REGISTER.findall(plugin.text):
            if base in self.types:
                jf = self.server.resolve(cls, plugin)
                self.types[base][type_id] = TypeInfo(self.server, jf, cls)
        events_file = self.server.file(self.server.by_path[pkg + "effect/TriggerEventType.java"])
        self.events = set(EVENT.findall(events_file.text))
        shape_file = self.server.file(self.server.by_path[pkg + "shape/TriggerVolumeShape.java"])
        self.shapes = {}
        for type_id, cls in SHAPE.findall(shape_file.text):
            self.shapes[type_id] = TypeInfo(self.server, self.server.resolve(cls, shape_file), cls)
        volume_file = self.server.file(self.server.by_path[pkg + "component/TriggerVolume.java"])
        self.volume = TypeInfo(self.server, volume_file, "TriggerVolume")
        self.tv_root = tv
        self._zc_types = {}

    def zc_type(self, base, type_id):
        """CommonLib's class registering `type_id` into `base`'s codec: a class extending `base` whose source names
        the Type in a string literal. None when this repository holds no such source yet."""
        key = (base, type_id)
        if key not in self._zc_types:
            found = None
            for name, paths in sorted(self.zc.by_name.items()):
                for p in paths:
                    jf = self.zc.file(p)
                    if jf.classes.get(name) == base and ('"%s"' % type_id) in jf.text:
                        found = TypeInfo(self.zc, jf, name)
                        # Its base codec (Event, Entry, ...) is the engine's, outside this repository.
                        engine_base = self.server.file(self.server.by_path[
                            "com/hypixel/hytale/builtin/triggervolumes/effect/%s.java" % base])
                        for k, expr in engine_base.keys.items():
                            found.keys.setdefault(k, expr)
                        break
                if found:
                    break
            self._zc_types[key] = found
        return self._zc_types[key]

    def type_of(self, base, type_id):
        """(TypeInfo, None) for a known Type, or (None, why) when it is unknown or its source is absent."""
        info = self.types[base].get(type_id)
        if info:
            return info, None
        if type_id.startswith("Zig"):
            info = self.zc_type(base, type_id)
            if info:
                return info, None
            return None, "CommonLib's %s %s has no source in this repository yet" % (base, type_id)
        return None, "the engine registers no %s Type %s" % (base, type_id)


def check_value(info, key, value, where, out):
    """Check one key and its value against the codec `info` declares it with; nested groups recurse."""
    expr = info.keys.get(key)
    if expr is None:
        out.append("%s: %s has no key %s" % (where, info.simple, key))
        return
    allowed = info.index.enum_values(info.file, expr)
    values = value if isinstance(value, list) else [value]
    if allowed is not None:
        for v in values:
            if not isinstance(v, str) or (v not in allowed["keys"] and v.lower() not in allowed["names"]):
                out.append("%s: %s.%s takes none of %r (it reads %s)" % (where, info.simple, key, v,
                                                                         ", ".join(sorted(allowed["keys"]))))
        return
    for v in values:
        if not isinstance(v, dict):
            continue
        if any(vc in expr for vc in VECTOR_CODECS):
            extra = set(v) - {"X", "Y", "Z"}
            if extra:
                out.append("%s: %s.%s is a vector, not %s" % (where, info.simple, key, sorted(extra)))
            continue
        nested = [TypeInfo(info.index, info.index.resolve(name, info.file), name)
                  for name in REFERENCED.findall(expr) if info.index.resolve(name, info.file) is not None]
        merged = {}
        for n in nested:
            merged.update(n.keys)
            for name in REFERENCED.findall(" ".join(n.keys.values())):
                deeper = info.index.resolve(name, n.file)
                if deeper is not None:
                    merged.update({k: e for k, e in info.index.class_keys(deeper, name).items() if k not in merged})
        if not merged:
            continue
        group = TypeInfo.__new__(TypeInfo)
        group.index, group.file, group.simple, group.keys = info.index, nested[0].file, key, merged
        for k, sub in v.items():
            if not k.startswith("$"):
                check_value(group, k, sub, where + "." + key, out)
