class skelbone(object):
    def __init__(self, id, name):
        self.id = id
        self.name = name
        self.pos = (0.0, 0.0, 0.0)
        self.rang = 0.0
        self.rax = (0.0, 0.0, 1.0)
        self.parent = None
        self.ass = []

class vertexlay(object):
    def __init__(self, name, eln):
        self.name = name
        self.eln = eln
        self.data = []
        self.fmt = "bare"

    def range(self):
        minv = maxv = self.data[0][0]
        for datum in self.data:
            for el in datum:
                minv = min(minv, el)
                maxv = max(maxv, el)
        return minv, maxv

    def write_f4(self, out):
        for datum in self.data:
            for el in datum:
                out.float32(el)
    def write_f2(self, out):
        for datum in self.data:
            for el in datum:
                out.float16(el)

    def write_sn4(self, out):
        maxv = 0
        for datum in self.data:
            for el in datum:
                maxv = max(maxv, abs(el))
        out.float32(maxv)
        for datum in self.data:
            for el in datum:
                out.snorm32(el / maxv)
    def write_sn2(self, out):
        maxv = 0
        for datum in self.data:
            for el in datum:
                maxv = max(maxv, abs(el))
        out.float32(maxv)
        for datum in self.data:
            for el in datum:
                out.snorm16(el / maxv)
    def write_sn1(self, out):
        maxv = 0
        for datum in self.data:
            for el in datum:
                maxv = max(maxv, abs(el))
        out.float32(maxv)
        for datum in self.data:
            for el in datum:
                out.snorm8(el / maxv)

    def write_un4(self, out):
        maxv = 0
        for datum in self.data:
            for el in datum:
                maxv = max(maxv, abs(el))
                if el < 0:
                    raise Exception("cannot encode negative numbers with un4")
        out.float32(maxv)
        for datum in self.data:
            for el in datum:
                out.unorm32(el / maxv)
    def write_un2(self, out):
        maxv = 0
        for datum in self.data:
            for el in datum:
                maxv = max(maxv, abs(el))
                if el < 0:
                    raise Exception("cannot encode negative numbers with un2")
        out.float32(maxv)
        for datum in self.data:
            for el in datum:
                out.unorm16(el / maxv)
    def write_un1(self, out):
        maxv = 0
        for datum in self.data:
            for el in datum:
                maxv = max(maxv, abs(el))
                if el < 0:
                    raise Exception("cannot encode negative numbers with un4")
        out.float32(maxv)
        for datum in self.data:
            for el in datum:
                out.unorm8(el / maxv)

    def write_rn4(self, out):
        minv, maxv = self.range()
        m, k = minv, (maxv - minv)
        out.float32(m).float32(k)
        for datum in self.data:
            for el in datum:
                out.unorm32((el - m) / k)
    def write_rn2(self, out):
        minv, maxv = self.range()
        m, k = minv, (maxv - minv)
        out.float32(m).float32(k)
        for datum in self.data:
            for el in datum:
                out.unorm16((el - m) / k)
    def write_rn1(self, out):
        minv, maxv = self.range()
        m, k = minv, (maxv - minv)
        out.float32(m).float32(k)
        for datum in self.data:
            for el in datum:
                out.unorm8((el - m) / k)

    def write_uvech(self, out):
        if self.eln != 3:
            raise Exception("cannot encode %i-vectors with uvec1" % (self.eln,))
        for datum in self.data:
            oct = haven.utils.uvec2oct(haven.utils.coord3(datum))
            hx, hy = round(oct.x * 7) & 0xf, round(oct.y * 7) & 0xf
            out.uint8((hx << 4) | hy)
    def write_uvec1(self, out):
        if self.eln != 3:
            raise Exception("cannot encode %i-vectors with uvec1" % (self.eln,))
        for datum in self.data:
            oct = haven.utils.uvec2oct(haven.utils.coord3(datum))
            out.snorm8(oct.x).snorm8(oct.y)
    def write_uvec2(self, out):
        if self.eln != 3:
            raise Exception("cannot encode %i-vectors with uvec2" % (self.eln,))
        for datum in self.data:
            oct = haven.utils.uvec2oct(haven.utils.coord3(datum))
            out.snorm16(oct.x).snorm16(oct.y)

    def write(self, out):
        for datum in self.data:
            if len(datum) != self.eln:
                raise Exception("datum length mismatch (%i != %i) in %s" % (len(datum), vlay.eln, vlay.lnm))
        if self.fmt == "bare":
            for datum in self.data:
                for el in datum:
                    out.float32(el)
        else:
            out.uint8(1)
            out.str(self.fmt)
            getattr(self, "write_" + self.fmt)(out)

class vertexbuf(object):
    def __init__(self):
        self.layers = {}
        self.allfaces = []
        self.mktan = False
        self.assbones = {}
        self.formats = {}
        self.bonefmt = None if oldres else "un1"

    def getlayer(self, nm, el):
        if nm not in self.layers:
            self.layers[nm] = vertexlay(nm, el)
        ret = self.layers[nm]
        if ret.eln != el:
            raise Exception("requested elements %i not matching current %i for %s" % (el, ret.eln, nm))
        return ret

    def haslayer(self, nm):
        return nm in self.layers

    @property
    def vlen(self):
        if len(self.layers) == 0:
            return 0
        vlen = None
        for lay in self.layers.values():
            if vlen is None:
                vlen = len(lay.data)
            elif len(lay.data) != vlen:
                raise Exception("non-matching vertex-data lengths")
        return vlen

    @classmethod
    def parse(cls, vbuf):
        def onechild(n, tn, zero = False):
            ls = n.getElementsByTagName(tn)
            if len(ls) != 1:
                if zero and len(ls) == 0:
                    return None
                raise ValueError("Wanted exactly one `%s' child in %s, has %d" % (tn, n, len(ls)))
            return ls[0]
        def attrs(n, conv, *attrs):
            return tuple([conv(n.getAttribute(x)) for x in attrs])
        self = cls()
        vpos = self.getlayer("pos", 3).data if (vbuf.getAttribute("positions") == "true") else None
        vnor = self.getlayer("nrm", 3).data if (vbuf.getAttribute("normals") == "true") else None
        mtex = int(vbuf.getAttribute("texture_coords") or "0")
        if mtex == 0:
            vtex = []
        elif mtex == 1:
            vtex = [self.getlayer("tex", 2).data]
        elif mtex == 2:
            vtex = [self.getlayer("tex", 2).data, self.getlayer("otex", 2).data]
        else:
            error("does not know how to interpret %i texcoords", mtex)
        for v in vbuf.getElementsByTagName("vertex"):
            if vpos is not None:
                pos = attrs(onechild(v, "position"), float, "x", "y", "z")
                pos = pos[0], -pos[2], pos[1]
                vpos.append(pos)
            if vnor is not None:
                nor = attrs(onechild(v, "normal"), float, "x", "y", "z")
                nor = nor[0], -nor[2], nor[1]
                vnor.append(nor)
            texcoords = v.getElementsByTagName("texcoord")
            if len(texcoords) != len(vtex):
                error("mismatching number of texcoords")
            for tl, tex in zip(vtex, texcoords):
                tex = attrs(tex, float, "u", "v")
                tl.append(tex)
        return self

    def merge(self, other):
        if len(self.layers) == 0:
            for lay in other.layers.values():
                self.getlayer(lay.name, lay.eln).data.extend(lay.data)
            return 0, self.vlen
        else:
            if set(self.layers.keys()) != set(other.layers.keys()):
                sys.stderr.write("mkres: error: submeshes do not share geometry structure\n")
                sys.stderr.write("%r %r\n" % (set(self.layers.keys()), set(other.layers.keys())))
                sys.exit(1)
            base = self.vlen
            for lay in self.layers.values():
                lay.data.extend(other.getlayer(lay.name, lay.eln).data)
            return base, other.vlen

    def mktangents(self):
        if not self.haslayer("pos") or not self.haslayer("tex"):
            error("positions and texture coordinates needed to calculate tangents")
        vpos = self.getlayer("pos", 3).data
        vtex = self.getlayer("tex", 2).data
        tan = [(0.0, 0.0, 0.0)] * len(vpos)
        bit = [(0.0, 0.0, 0.0)] * len(vpos)
        nbroken = 0
        for v0, v1, v2 in self.allfaces:
            q1 = (vpos[v1][0] - vpos[v0][0], vpos[v1][1] - vpos[v0][1], vpos[v1][2] - vpos[v0][2])
            q2 = (vpos[v2][0] - vpos[v0][0], vpos[v2][1] - vpos[v0][1], vpos[v2][2] - vpos[v0][2])
            r1 = (vtex[v1][0] - vtex[v0][0], vtex[v1][1] - vtex[v0][1])
            r2 = (vtex[v2][0] - vtex[v0][0], vtex[v2][1] - vtex[v0][1])
            try:
                I = 1 / ((r2[0] * r1[1]) - (r1[0] * r2[1]))
            except ZeroDivisionError:
                nbroken += 1
            else:
                T = tuple(I * (q2[i] * r1[1] - q1[i] * r2[1]) for i in range(3))
                B = tuple(I * (q1[i] * r2[0] - q2[i] * r1[0]) for i in range(3))
                for vi in v0, v1, v2:
                    tan[vi] = tuple(tan[vi][i] + T[i] for i in range(3))
                    bit[vi] = tuple(bit[vi][i] + T[i] for i in range(3))
        def normalize(vec):
            l = math.sqrt(sum(e * e for e in vec))
            if l < 1e-20:
                return (0.0, 0.0, 1.0)
            return tuple(e / l for e in vec)
        for i in range(len(tan)):
            tan[i] = normalize(tan[i])
            bit[i] = normalize(bit[i])
        if nbroken > 0:
            inform("%i broken faces when baking normals", nbroken)
        return tan, bit

    def chformat(self, nm, fnm, fmt):
        if self.haslayer(nm):
            lay = self.layers.pop(nm)
            lay.name = fnm
            lay.fmt = fmt
            self.layers[lay.name] = lay

    def finalize(self):
        if self.mktan:
            if self.haslayer("tan") or self.haslayer("bit"):
                error("trying to bake tangent for meshes that already have them")
            vtan, vbit = self.mktangents()
            self.getlayer("tan", 3).data.extend(vtan)
            self.getlayer("bit", 3).data.extend(vbit)
        if "pos" not in self.formats: self.formats["pos"] = "sn2"
        if "nrm" not in self.formats: self.formats["nrm"] = "uvec1"
        if "tan" not in self.formats: self.formats["tan"] = "uvec1"
        if "bit" not in self.formats: self.formats["bit"] = "uvec1"
        if "tex" not in self.formats and self.haslayer("tex"):
            if self.getlayer("tex", 2).range()[0] >= 0:
                self.formats["tex"] = "un2"
            else:
                self.formats["tex"] = "sn2"
        if "otex" not in self.formats and self.haslayer("otex"):
            if self.getlayer("otex", 2).range()[0] >= 0:
                self.formats["otex"] = "un2"
            else:
                self.formats["otex"] = "sn2"
        for lnm, nfmt in self.formats.items():
            self.chformat(lnm, lnm + "2", nfmt)

    def normweights(self):
        mina = min(min(idx for idx, w in bone.ass) for bone in self.assbones.values() if len(bone.ass) > 0)
        maxa = max(max(idx for idx, w in bone.ass) for bone in self.assbones.values() if len(bone.ass) > 0)
        n = maxa + 1 - mina
        vass = [[] for _ in range(n)]
        for bone in self.assbones.values():
            for idx, w in bone.ass:
                vass[idx - mina].append((bone, w))
        for bass in vass:
            if len(bass) > 0:
                s = sum(ass[1] for ass in bass)
                for i in range(len(bass)):
                    bass[i] = (bass[i][0], bass[i][1] / s)
        for bone in self.assbones.values():
            bone.ass = []
        for i, bass in enumerate(vass):
            for bone, w in bass:
                bone.ass.append((i + mina, w))

    def writebones(self, lay):
        if len(self.assbones) > 0:
            pbuf = haven.binfmt.encbuf()
            mass = 0
            nass = [0] * self.vlen
            if self.bonefmt == "f4":
                benc = pbuf.float32
                mzr = 0
            elif self.bonefmt == "un2":
                benc = pbuf.unorm16
                mzr = 1
            elif self.bonefmt == "un1":
                benc = pbuf.unorm8
                mzr = 3
            elif self.bonefmt is None:
                benc = pbuf.float32
                mzr = 0
            else:
                error("unknown bone-weight format: %s", self.bonefmt)
            if any(any(w > 1 for idx, w in bone.ass) for bone in self.assbones.values()):
                self.normweights()
            for bone in sorted(self.assbones.values(), key=lambda bone: bone.name):
                ass = bone.ass
                if len(ass) == 0:
                    continue
                pbuf.str(bone.name)
                ass.sort(key=lambda o: o[0])
                i = 0
                while i < len(ass):
                    st = ass[i][0]
                    o = i
                    rw = []
                    while True:
                        vn, w = ass[o]
                        rw.append(w)
                        cnass = nass[vn] = nass[vn] + 1
                        if cnass > mass:
                            mass = cnass
                        o += 1
                        if o == len(ass): break
                        nvn = ass[o][0]
                        if nvn - vn == 1:
                            pass
                        elif 1 < nvn - vn <= mzr + 1:
                            for u in range(nvn - vn - 1):
                                rw.append(0.0)
                        else:
                            break
                    pbuf.uint16(len(rw)).uint16(st)
                    for w in rw:
                        benc(w)
                    i = o
                pbuf.uint16(0).uint16(0)
            pbuf.str("")
            if mass > 0:
                if self.bonefmt is None:
                    with sublaybuf("bones", lay) as abuf:
                        abuf.uint8(mass)
                        abuf.extend(pbuf)
                else:
                    with sublaybuf("bones2", lay) as abuf:
                        abuf.uint8(1)
                        abuf.str(self.bonefmt)
                        abuf.uint8(mass)
                        abuf.extend(pbuf)

    def write(self):
        if len(self.layers) == 0:
            raise Exception("empty vbuf")
        self.finalize()
        with laystream("vbuf2") as lay:
            lay.uint8(1).uint16(0).uint16(self.vlen)
            for vlay in self.layers.values():
                with sublaybuf(vlay.name, lay) as abuf:
                    vlay.write(abuf)
            self.writebones(lay)

    def mkresvbuf(self):
        ret = None
        for lay in self.layers.values():
            if ret is None:
                ret = haven.res.vbuf()
                ret.nv = len(lay.data)
            else:
                if len(lay.data) != ret.nv:
                    raise Exception("vertex-number mismatch (%i in %s != %i)" % (len(lay.data), lay.name, ret.nv))
            if lay.name in {"pos", "pos2"}:
                assert lay.eln == 3
                arr = haven.res.vpos()
            elif lay.name == {"nrm", "nrm2"}:
                assert lay.eln == 3
                arr = haven.res.vnorm()
            else:
                continue
            for datum in lay.data:
                for el in datum:
                    arr.data.append(el)
            ret.arrays.append(arr)
        return ret

    _glob = None
    @classmethod
    def get(cls):
        if cls._glob is None:
            cls._glob = cls()
        return cls._glob

matids = {}
materials = {}
def getmatid(matnm):
    if matnm not in matids:
        if len(matids) == 0:
            matids[matnm] = 1
        else:
            matids[matnm] = max(matids.values()) + 1
    return matids[matnm]

class mesh(object):
    collected = []

    def __init__(self):
        self.faces = []
        self.matnm = None
        self.id = -1
        self.ref = -1
        self.rdat = {}

    def fork(self):
        ret = mesh()
        ret.matnm = self.matnm
        ret.id = self.id
        ret.ref = self.ref
        ret.rdat = dict(self.rdat)
        return ret

    def stripify(self, lay):
        perm = [(0, 1, 2), (1, 2, 0), (2, 0, 1)]
        left = set(self.faces)
        vsorted = {face: tuple(sorted(face)) for face in left}
        edges = {}
        for face in left:
            for a, b, c in perm:
                edges.setdefault((face[a], face[b]), []).append((face, face[c]))

        def remove(face):
            left.remove(face)
            for a, b, c in perm:
                edges[(face[a], face[b])].remove((face, face[c]))

        def encdelta(dst, pick, delta):
            assert delta != 0 and -65535 <= delta <= 65535
            if delta > 0:
                delta -= 1
                if delta < 0x20:
                    dst.uint8((pick << 7) | delta)
                elif delta < 0x1000:
                    dst.uint8((pick << 7) | 0x40 | (delta & 0x3f))
                    dst.uint8(delta >> 6)
                else:
                    dst.uint8((pick << 7) | 0x40 | (delta & 0x3f))
                    dst.uint8(0x80 | ((delta >> 6) & 0x7f))
                    dst.uint8(delta >> 13)
            else:
                if -0x20 <= delta:
                    dst.uint8((pick << 7) | (delta & 0x3f))
                elif -0x1000 <= delta:
                    dst.uint8((pick << 7) | 0x40 | (delta & 0x3f))
                    dst.uint8((delta >> 6) & 0x7f)
                else:
                    dst.uint8((pick << 7) | 0x40 | (delta & 0x3f))
                    dst.uint8(0x80 | ((delta >> 6) & 0x7f))
                    dst.uint8((delta >> 13) & 0x7f)

        lens = {}
        while len(left) > 0:
            start = min(left, key=lambda face: vsorted[face])
            remove(start)
            sbuf = haven.binfmt.encbuf()
            slen = 0
            nface = None
            pstart = start
            for a, b, c in perm:
                for cface in edges.get((start[b], start[a]), ()):
                    if cface[1] != start[b]:
                        if nface is None or cface[1] < nface[1]:
                            nface = cface
                            pface = pstart = (start[c], start[a], start[b])
                            face = (start[b], start[a], cface[1])
                            ppick = 1
            while nface and slen < 255:
                remove(nface[0])
                nvert = nface[1]
                encdelta(sbuf, ppick, nvert - pface[2])
                slen += 1
                pface = face
                nface = None
                for cface in edges.get((pface[2], pface[1]), ()):
                    if cface[1] != nvert:
                        if nface is None or cface[1] < nface[1]:
                            nface = cface
                            face = (pface[2], pface[1], cface[1])
                            ppick = 1
                for cface in edges.get((pface[0], pface[2]), ()):
                    if cface[1] != nvert:
                        if nface is None or cface[1] < nface[1]:
                            nface = cface
                            face = (pface[0], pface[2], cface[1])
                            ppick = 0
            lay.uint16(pstart[0])
            encdelta(lay, 0, pstart[1] - pstart[0])
            encdelta(lay, 0, pstart[2] - pstart[1])
            lay.uint8(slen)
            lay.extend(bytes(sbuf))
            lens[slen] = lens.get(slen, 0) + 1
        # print(sorted(lens.items()))

    def write(self):
        stripify = True
        with laystream("mesh") as lay:
            fl = 1
            odat = haven.binfmt.encbuf()
            if self.id != -1:
                fl |= 2
                odat.int16(self.id)
            if self.ref != -1:
                fl |= 4
                odat.int16(self.ref)
            if self.rdat:
                fl |= 8
                for k, v in self.rdat.items():
                    odat.str(k).str(v)
                odat.str("")
            if stripify:
                fl |= 32
            lay.uint8(fl).uint16(len(self.faces))
            if self.matnm is None:
                lay.int16(-1)
            else:
                lay.int16(getmatid(self.matnm))
            lay.extend(odat)
            if stripify:
                self.stripify(lay)
            else:
                for face in self.faces:
                    lay.enstruct("<HHH", *face)

def writemesh(md):
    if clearmeshrefs not in delayed:
        delayed.append(clearmeshrefs)
    mesh.collected.append(md)
    delayed.append(md.write)

def writeoptmesh(md, bones):
    min, max = None, None
    for face in md.faces:
        for vert in face:
            if min is None or vert < min:
                min = vert
            if max is None or vert > max:
                max = vert
    reass = [None] * (max + 1 - min)
    oass = [None] * (max + 1 - min)
    for i in range(len(reass)):
        reass[i] = []
    for bone in bones:
        rem = []
        for vert, weight in bone.ass:
            if vert >= min and vert <= max:
                reass[vert - min].append((bone, weight))
            else:
                rem.append((vert, weight))
        bone.ass = rem
    parts = {}
    for i in range(len(reass)):
        vass = reass[i]
        vert = i + min
        if len(vass) != 0:
            mbone = None
            mw = None
            for abone, weight in vass:
                if mw is None or weight > mw:
                    mbone, mw = abone, weight
            for abone, weight in vass:
                if abone != mbone and weight > mw / 10.0:
                    error("multiple significant weights on vertex flagged for optimization (on %s)", [abone.name for abone, weight in vass])
            oass[i] = mbone
            mbone.ass.append((vert, 1.0))
        else:
            oass[i] = None
    for face in md.faces:
        bone = oass[face[0] - min]
        for vert in face:
            if oass[vert - min] != bone:
                error("vertices in one face assigned to differing bones (%s and %s)", oass[vert - min].name, bone.name)
        pmesh = parts.get(bone)
        if pmesh is None:
            pmesh = parts[bone] = md.fork()
        pmesh.faces.append(face)
    for part in parts.values():
        writemesh(part)

def parseboneass(assl, bonel, base):
    for ass in assl.getElementsByTagName("vertexboneassignment"):
        ba = bonel[int(ass.getAttribute("boneindex"))]
        ba.append((int(ass.getAttribute("vertexindex")) + base, float(ass.getAttribute("weight"))))

def parsemeshanims(poses, vbase):
    def fmtvn32(lay, off):
        idx, x, y, z = off
        lay.float32(x).float32(y).float32(z)
        lay.float32(0).float32(0).float32(0)
    def fmtv9995(lay, off):
        idx, x, y, z = off
        lay.uint32(haven.binfmt.efloat9995(x, y, z))
    def fmtv16(lay, off):
        idx, x, y, z = off
        lay.float16(x).float16(y).float16(z)
    def fmtvnorm8(lay, off):
        idx, x, y, z = off
        lay.unorm8((x - xl) / (xh - xl)).unorm8((y - yl) / (yh - yl)).unorm8((z - zl) / (zh - zl))

    byname = {}
    for pose in poses.getElementsByTagName("pose"):
        nm = pose.getAttribute("name")
        if pose.getAttribute("target") != "mesh":
            warn("cannot handle non-shared poses; ignoring %s", nm)
            continue
        parts = nm.split(":")
        anm = parts[0]
        if anm not in byname:
            byname[anm] = {"dur": 0.5, "id": -1, "frames": [], "ctime": 0.0, "rnd": False, "name": anm}
        for part in parts[1:]:
            if part[0] == "d":
                byname[anm]["dur"] = float(part[1:]) / 1000.0
            elif part[0] == "i":
                byname[anm]["id"] = int(part[1:])
            elif part == "r":
                byname[anm]["rnd"] = True
        offsets = []
        for off in pose.getElementsByTagName("poseoffset"):
            idx = int(off.getAttribute("index")) + vbase
            x, y, z = float(off.getAttribute("x")), float(off.getAttribute("y")), float(off.getAttribute("z"))
            y, z = -z, y
            if abs(x) < 0.0001 and abs(y) < 0.0001 and abs(z) < 0.0001:
                continue
            offsets.append((idx, x, y, z))
        offsets.sort(key=lambda o: o[0])
        ctime = byname[anm]["ctime"]
        byname[anm]["frames"].append((ctime, offsets))
        ctime += byname[anm]["dur"]
        byname[anm]["ctime"] = ctime
    for anim in byname.values():
        for time, offsets in anim["frames"]:
            if len(offsets) > 0:
                break
        else:
            warn("ignoring empty mesh-animation: %s", anim["name"])
            continue
        with laystream("manim") as lay:
            lay.uint8(1)
            lay.int16(anim["id"])
            lay.uint8(1 if anim["rnd"] else 0)
            lay.float32(anim["ctime"])
            for time, offsets in anim["frames"]:
                xl = xh = yl = yh = zl = zh = 0
                for idx, x, y, z in offsets:
                    xl = min(xl, x); xh = max(xh, x)
                    yl = min(yl, y); yh = max(yh, y)
                    zl = min(zl, z); zh = max(zh, z)
                fmt = 3
                lay.uint8(fmt)
                lay.float32(time)
                lay.uint16(len(offsets))
                if fmt == 4:
                    lay.float16(xl).float16(xh - xl)
                    lay.float16(yl).float16(yh - yl)
                    lay.float16(zl).float16(zh - zl)
                oi = 0
                while oi < len(offsets):
                    vi = offsets[oi][0]
                    rl = 1
                    while (oi + rl) < len(offsets) and offsets[oi + rl][0] == vi + rl:
                        rl += 1
                    lay.uint16(vi).uint16(rl)
                    for i in range(rl):
                        if fmt == 1:
                            fmtvn32(lay, offsets[oi + i])
                        elif fmt == 2:
                            fmtv9995(lay, offsets[oi + i])
                        elif fmt == 3:
                            fmtv16(lay, offsets[oi + i])
                        elif fmt == 4:
                            fmtvnorm8(lay, offsets[oi + i])
                    oi += rl
            lay.uint8(0)

meshseq = 0
def parsemesh(infile):
    global meshseq
    gbuf = vertexbuf.get()
    info = os.path.basename(infile).split('.')[:-1]
    meshref = meshseq
    meshseq += 1
    meshid = None
    for part in info[1:]:
        try:
            meshid = int(part)
        except ValueError:
            if part == "tan":
                gbuf.mktan = True
            elif part[0] == "p":
                pass
            else:
                warn("unrecognized mesh name part: %s", part)
    def onechild(n, tn, zero = False):
        ls = n.getElementsByTagName(tn)
        if len(ls) != 1:
            if zero and len(ls) == 0:
                return None
            raise ValueError("Wanted exactly one `%s' child in %s, has %d" % (tn, n, len(ls)))
        return ls[0]
    def attrs(n, conv, *attrs):
        return tuple([conv(n.getAttribute(x)) for x in attrs])
    doc = xml.dom.minidom.parse(infile)
    submeshes = onechild(doc, "submeshes").getElementsByTagName("submesh")
    for sm in submeshes:
        parts = sm.getAttribute("material").split(":")
        matnm = parts[0]
        if matnm == "obst" or matnm.startswith("obst:"):
            return parseobstmesh(infile)
    bonel = None
    if doc.getElementsByTagName("skeletonlink"):
        skelfile = os.path.join(os.path.dirname(infile), onechild(doc, "skeletonlink").getAttribute("name"))
        bones = parsebones(xml.dom.minidom.parse(skelfile)).values()
        bones = [gbuf.assbones.setdefault(bone.name, bone) for bone in bones]
        bonel = [None] * (max(bone.id for bone in bones) + 1)
        for bone in bones:
            bonel[bone.id] = bone.ass
    sharebase = None
    if doc.getElementsByTagName("sharedgeometry"):
        vbuf = onechild(onechild(doc, "sharedgeometry"), "vertexbuffer")
        sharebase, sharenum = gbuf.merge(vertexbuf.parse(vbuf))
        assl = onechild(doc, "boneassignments", True)
        if assl is not None:
            parseboneass(assl, bonel, sharebase)
    for sm in submeshes:
        optskel = False
        res = mesh()
        res.ref = meshref
        parts = sm.getAttribute("material").split(":")
        res.matnm = parts[0]
        if res.matnm == "" or res.matnm == "ignore":
            res.matnm = None
        for part in parts[1:]:
            if part[:1] == "i":
                res.id = int(part[1:], 0)
            elif part[:1] == "v":
                res.rdat["vm"] = str(int(part[1:], 0))
            elif part[:2] == "cz":
                res.rdat["cz"] = str(int(part[2:]))
            elif part == "exact":
                gbuf.formats["pos"] = "f4"
                gbuf.bonefmt = "f4"
            elif part[:2] == "of":
                p = part.index('=')
                if part[2:p] == "bones":
                    gbuf.bonefmt = part[p + 1:]
                else:
                    gbuf.formats[part[2:p]] = part[p + 1:]
            elif part == "optskel":
                optskel = True
            else:
                warn("unknown mesh name part: %s", part)
        if sm.getAttribute("id"):
            res.id = int(sm.getAttribute("id"), 0)
        if meshid is not None and res.id < 0:
            res.id = meshid
        if sm.getAttribute("vid"):
            res.rdat["vm"] = str(int(sm.getAttribute("vid"), 0))
        if sm.getAttribute("compz"):
            res.rdat["cz"] = str(int(sm.getAttribute("compz"), 0))
        if sm.getAttribute("usesharedvertices") != "false":
            if sharebase is None:
                error("mesh uses shared geometry, but there is no shared geometry")
            base = sharebase
            c = sharenum
        else:
            vbuf = onechild(onechild(sm, "geometry"), "vertexbuffer")
            base, c = gbuf.merge(vertexbuf.parse(vbuf))
            assl = onechild(sm, "boneassignments", True)
            if assl is not None:
                parseboneass(assl, bonel, base)
        for f in onechild(sm, "faces").getElementsByTagName("face"):
            face = attrs(f, int, "v1", "v2", "v3")
            for v in face:
                if v < 0 or v >= c:
                    error("invalid vertex index");
            face = tuple([v + base for v in face])
            res.faces.append(face)
            gbuf.allfaces.append(face)
        if optskel:
            writeoptmesh(res, bones)
        else:
            writemesh(res)
    if doc.getElementsByTagName("poses"):
        parsemeshanims(onechild(doc, "poses"), sharebase)
    if not gbuf.write in delayed:
        delayed.append(gbuf.write)
