/**
 * JSON Schema ↔ visual field tree.
 *
 * The UI works against a tree of {@link SchemaField}s — a flat editing
 * model that maps cleanly to form rows. We round-trip it through the
 * standard JSON Schema shape so the source-mode editor + the on-disk
 * `inboundFormat` stay vendor-neutral.
 *
 * Supports nested objects, arrays of any element type (including arrays
 * of objects → arrays of object → ...), enums on scalar leaves, and
 * required-flag tracking via the parent's "required" array.
 */

export type SchemaField = {
  name: string
  type: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array'
  required: boolean
  description?: string
  enums?: string
  children?: SchemaField[]
  items?: SchemaField
}

const SCALAR = new Set(['string', 'number', 'integer', 'boolean'])

/** JSON Schema → tree. Top-level schema must be {type: object} with `properties`. */
export function schemaToFields(schema: any): SchemaField[] {
  if (!schema || typeof schema !== 'object') return []
  const required: string[] = Array.isArray(schema.required) ? schema.required : []
  const props = schema.properties ?? {}
  return Object.entries(props).map(([name, def]: [string, any]) =>
    propToField(name, def, required.includes(name)))
}

function propToField(name: string, def: any, required: boolean): SchemaField {
  const rawType = Array.isArray(def?.type) ? def.type[0] : (def?.type ?? 'string')
  const type = rawType as SchemaField['type']
  const f: SchemaField = {
    name,
    type,
    required,
    description: def?.description,
    enums: def?.enum ? def.enum.join(',') : undefined
  }
  if (type === 'object') {
    f.children = schemaToFields(def)        // recurse on properties + required
  } else if (type === 'array') {
    // items can be a schema or a schema array; we only support the common
    // single-schema case. Anonymous element name is "(item)".
    const itemDef = def?.items ?? { type: 'string' }
    f.items = propToField('(item)', itemDef, false)
  }
  return f
}

/** Tree → JSON Schema. */
export function fieldsToSchema(fields: SchemaField[]): any {
  const required: string[] = []
  const properties: Record<string, any> = {}
  for (const f of fields) {
    if (!f.name) continue
    if (f.required) required.push(f.name)
    properties[f.name] = fieldToProp(f)
  }
  const out: any = { type: 'object', properties }
  if (required.length) out.required = required
  return out
}

function fieldToProp(f: SchemaField): any {
  const out: any = { type: f.type }
  if (f.description) out.description = f.description
  if (f.enums && SCALAR.has(f.type)) {
    const items = f.enums.split(',').map(s => s.trim()).filter(Boolean)
    if (items.length) {
      out.enum = f.type === 'string' ? items : items.map(v => Number(v))
    }
  }
  if (f.type === 'object') {
    const innerSchema = fieldsToSchema(f.children ?? [])
    out.properties = innerSchema.properties
    if (innerSchema.required) out.required = innerSchema.required
  } else if (f.type === 'array') {
    out.items = f.items ? fieldToProp(f.items) : { type: 'string' }
  }
  return out
}
