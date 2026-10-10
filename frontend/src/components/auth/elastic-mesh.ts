export type ElasticMeshProfileName = 'login' | 'ambient';

export type MeshEdgeKind = 'warp' | 'weft' | 'cross';

export const MAX_MESH_NODES = 400;
export const MAX_MESH_DPR = 2;
export const XPBD_FIXED_STEP_SECONDS = 1 / 120;
export const MIN_TEAR_SPEED = 0.38;

export const FIXED_STEP_SECONDS = XPBD_FIXED_STEP_SECONDS;
export const MAX_BROKEN_CONSTRAINTS = 10;
export const MAX_ADHESION_CONSTRAINTS = 8;

export interface ElasticMeshProfile {
  readonly name: ElasticMeshProfileName;
  readonly compact: boolean;
  readonly columns: number;
  readonly rows: number;
  readonly damping: number;
  readonly solverIterations: number;
  readonly maxSubsteps: number;
  readonly structuralCompliance: number;
  readonly crossCompliance: number;
  readonly bendingCompliance: number;
  readonly restShapeCompliance: number;
  readonly structuralBreakStrain: number;
  readonly pointerHoverRadius: number;
  readonly pointerPressureStrength: number;
  readonly pointerContactRadius: number;
  readonly pointerContactLimit: number;
  readonly pointerCompliance: number;
  readonly pointerTearRadius: number;
  readonly maxTornConnections: number;
  readonly maxAdhesionConstraints: number;
  readonly adhesionCaptureRadius: number;
  readonly adhesionMaxRelativeSpeed: number;
  readonly adhesionCooldown: number;
  readonly adhesionCureDuration: number;
  readonly adhesionStartCompliance: number;
  readonly adhesionEndCompliance: number;
  readonly adhesionBreakRatio: number;
  readonly waveSpeed: number;
  readonly waveRadius: number;
  readonly fiberStrands: number;
}

export interface SilkWebNode {
  readonly x: number;
  readonly y: number;
  readonly inverseMass: number;
  readonly row: number;
  readonly column: number;
}

export interface SilkWebEdge {
  readonly first: number;
  readonly second: number;
  readonly kind: MeshEdgeKind;
  readonly breakable: boolean;
}

export interface SilkWebBend {
  readonly first: number;
  readonly middle: number;
  readonly second: number;
}

export type SilkFiberKind = 'warp' | 'weft';

export interface SilkPalette {
  readonly primary: string;
  readonly kinetic: string;
}

export interface SilkStroke {
  readonly color: string;
  readonly opacity: number;
  readonly width: number;
}

export class SilkFiber {
  constructor(
    public readonly kind: SilkFiberKind,
    public readonly particleIndices: readonly number[],
    public readonly phase: number,
  ) {}
}

export class SilkMaterial {
  public readonly baseFiberOpacity: number;
  public readonly baseFiberWidth: number;

  constructor(
    public readonly palette: SilkPalette,
    public readonly compact: boolean,
  ) {
    this.baseFiberOpacity = compact ? 0.18 : 0.16;
    this.baseFiberWidth = compact ? 0.62 : 0.56;
  }

  fiberStroke(
    fiber: SilkFiber,
    strandIndex: number,
    strandCount: number,
    tension: number,
  ): SilkStroke {
    const phase = strandCount <= 1 ? 0 : strandIndex / (strandCount - 1);
    const phaseOpacity = 0.88 + Math.sin(fiber.phase + phase * 3.64) * 0.12;
    return {
      color: this.palette.primary,
      opacity: clampValue(this.baseFiberOpacity * phaseOpacity + tension * 0.16, 0.13, 0.46),
      width: this.baseFiberWidth + tension * 0.28,
    };
  }

  crossStroke(tension: number, pointerProximity: number): SilkStroke | null {
    if (tension < 0.18 && pointerProximity < 0.12) {
      return null;
    }
    return {
      color: this.palette.kinetic,
      opacity: clampValue(0.1 + tension * 0.32 + pointerProximity * 0.28, 0.12, 0.62),
      width: 0.56 + tension * 0.46 + pointerProximity * 0.18,
    };
  }

  adhesionStroke(maturity: number, tension: number): SilkStroke {
    return {
      color: this.palette.kinetic,
      opacity: clampValue(0.42 + maturity * 0.3 + tension * 0.2, 0.42, 0.88),
      width: (this.compact ? 0.82 : 1.02) + maturity * 0.58,
    };
  }

  tearStroke(opacity: number, leading: boolean): SilkStroke {
    return {
      color: this.palette.kinetic,
      opacity: clampValue(opacity * (leading ? 0.86 : 0.68), 0, 0.86),
      width: leading ? 1.15 : 0.92,
    };
  }
}

export class SilkWebTopology {
  constructor(
    public readonly nodes: readonly SilkWebNode[],
    public readonly edges: readonly SilkWebEdge[],
    public readonly bends: readonly SilkWebBend[],
    public readonly fibers: readonly SilkFiber[],
    public readonly columns: number,
    public readonly rows: number,
  ) {}
}

export function clampValue(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value));
}

export function resolveCanvasDpr(devicePixelRatio: number) {
  return clampValue(Number.isFinite(devicePixelRatio) ? devicePixelRatio : 1, 1, MAX_MESH_DPR);
}

function createProfile(
  name: ElasticMeshProfileName,
  compact: boolean,
): ElasticMeshProfile {
  const login = name === 'login';
  return {
    name,
    compact,
    columns: compact ? (login ? 14 : 12) : (login ? 24 : 20),
    rows: compact ? (login ? 9 : 8) : (login ? 16 : 13),
    damping: compact ? 0.974 : 0.98,
    solverIterations: compact ? 3 : 4,
    maxSubsteps: compact ? 4 : 6,
    structuralCompliance: compact ? 0.00011 : 0.00007,
    crossCompliance: compact ? 0.00024 : 0.00016,
    bendingCompliance: compact ? 0.00042 : 0.00028,
    restShapeCompliance: compact ? 0.0048 : 0.0036,
    structuralBreakStrain: compact ? 0.38 : 0.32,
    pointerHoverRadius: compact ? 118 : 178,
    pointerPressureStrength: compact ? 22 : 30,
    pointerContactRadius: compact ? 76 : 118,
    pointerContactLimit: compact ? 10 : 24,
    pointerCompliance: compact ? 0.00058 : 0.00036,
    pointerTearRadius: compact ? 15 : 19,
    maxTornConnections: compact ? 4 : MAX_BROKEN_CONSTRAINTS,
    maxAdhesionConstraints: compact ? 3 : MAX_ADHESION_CONSTRAINTS,
    adhesionCaptureRadius: compact ? 38 : 48,
    adhesionMaxRelativeSpeed: compact ? 84 : 112,
    adhesionCooldown: 340,
    adhesionCureDuration: 560,
    adhesionStartCompliance: 0.0065,
    adhesionEndCompliance: 0.00014,
    adhesionBreakRatio: 1.78,
    waveSpeed: compact ? 250 : 330,
    waveRadius: compact ? 240 : 460,
    fiberStrands: compact ? 3 : 5,
  };
}

export function resolveElasticMeshProfile(
  name: ElasticMeshProfileName = 'login',
  compact = false,
) {
  return createProfile(name, compact);
}

function edgeKey(first: number, second: number) {
  return first < second ? `${first}:${second}` : `${second}:${first}`;
}

export class SilkWebTopologyBuilder {
  constructor(
    private readonly width: number,
    private readonly height: number,
    private readonly profile: ElasticMeshProfile,
  ) {}

  build() {
    const { columns, rows } = this.profile;
    const margin = this.profile.compact ? 12 : 20;
    const spacingX = (this.width + margin * 2) / Math.max(1, columns - 1);
    const spacingY = (this.height + margin * 2) / Math.max(1, rows - 1);
    const nodes: SilkWebNode[] = [];
    const indexAt = (row: number, column: number) => row * columns + column;

    for (let row = 0; row < rows; row += 1) {
      for (let column = 0; column < columns; column += 1) {
        const boundary = row === 0 || row === rows - 1 || column === 0 || column === columns - 1;
        const phase = row * 12.9898 + column * 78.233;
        const jitterX = boundary ? 0 : Math.sin(phase) * spacingX * 0.13;
        const jitterY = boundary ? 0 : Math.cos(phase * 0.87) * spacingY * 0.12;
        const stagger = boundary ? 0 : (row % 2 === 0 ? -1 : 1) * spacingX * 0.08;
        nodes.push({
          x: -margin + column * spacingX + jitterX + stagger,
          y: -margin + row * spacingY + jitterY,
          inverseMass: boundary ? 0 : 1,
          row,
          column,
        });
      }
    }

    const edges: SilkWebEdge[] = [];
    const seenEdges = new Set<string>();
    const addEdge = (first: number, second: number, kind: MeshEdgeKind) => {
      const key = edgeKey(first, second);
      if (first === second || seenEdges.has(key)) {
        return;
      }
      seenEdges.add(key);
      edges.push({
        first,
        second,
        kind,
        breakable: nodes[first].inverseMass > 0 && nodes[second].inverseMass > 0,
      });
    };

    for (let row = 0; row < rows; row += 1) {
      for (let column = 0; column < columns; column += 1) {
        const current = indexAt(row, column);
        if (column + 1 < columns) {
          addEdge(current, indexAt(row, column + 1), 'weft');
        }
        if (row + 1 < rows) {
          addEdge(current, indexAt(row + 1, column), 'warp');
        }
        if (row + 1 < rows && column + 1 < columns) {
          if ((row + column) % 2 === 0) {
            addEdge(current, indexAt(row + 1, column + 1), 'cross');
          } else {
            addEdge(indexAt(row, column + 1), indexAt(row + 1, column), 'cross');
          }
        }
      }
    }

    const edgeKeys = new Set(edges.map((edge) => edgeKey(edge.first, edge.second)));
    const bends: SilkWebBend[] = [];
    const addBend = (first: number, middle: number, second: number) => {
      if (edgeKeys.has(edgeKey(first, middle)) && edgeKeys.has(edgeKey(middle, second))) {
        bends.push({ first, middle, second });
      }
    };
    for (let row = 0; row < rows; row += 1) {
      for (let column = 1; column + 1 < columns; column += 1) {
        addBend(indexAt(row, column - 1), indexAt(row, column), indexAt(row, column + 1));
      }
    }
    for (let column = 0; column < columns; column += 1) {
      for (let row = 1; row + 1 < rows; row += 1) {
        addBend(indexAt(row - 1, column), indexAt(row, column), indexAt(row + 1, column));
      }
    }

    const fibers: SilkFiber[] = [];
    for (let row = 0; row < rows; row += 1) {
      fibers.push(new SilkFiber(
        'weft',
        Array.from({ length: columns }, (_, column) => indexAt(row, column)),
        row * 0.73,
      ));
    }
    for (let column = 0; column < columns; column += 1) {
      fibers.push(new SilkFiber(
        'warp',
        Array.from({ length: rows }, (_, row) => indexAt(row, column)),
        column * 0.61,
      ));
    }

    return new SilkWebTopology(nodes, edges, bends, fibers, columns, rows);
  }
}
