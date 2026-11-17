/**
 * ESLint rule `homer/max-props` (GH #311): caps the props a component takes, the console's counterpart of
 * the backend's parameter cap. A component is a function named in PascalCase — a declaration, or a
 * variable holding a function (directly or wrapped, as in `memo(...)`). Its props are the keys its first
 * parameter destructures, or, for an undestructured parameter, the members of its type when that type is
 * written inline or declared in the same file. A props type it cannot resolve is not counted.
 */

const isComponentName = (name) => /^[A-Z]/.test(name ?? '');

const isFunction = (node) => node?.type === 'ArrowFunctionExpression' || node?.type === 'FunctionExpression';

/** The function a `const X = ...` initializer holds: the initializer itself, or the first function argument of a wrapper call. */
function functionOf(init) {
  if (isFunction(init)) return init;
  if (init?.type === 'CallExpression') return init.arguments.find(isFunction) ?? null;
  return null;
}

/** Interfaces and type aliases declared at the top level of the file, exported or not, by name. */
function topLevelTypes(program) {
  const types = new Map();
  for (const statement of program.body) {
    const declaration = statement.type.startsWith('Export') ? statement.declaration : statement;
    if (declaration?.type === 'TSInterfaceDeclaration' || declaration?.type === 'TSTypeAliasDeclaration') {
      types.set(declaration.id.name, declaration);
    }
  }
  return types;
}

/** Member count of a props type, or null when it is neither inline nor declared in this file as an interface or object type. */
function typeMemberCount(type, types) {
  if (type?.type === 'TSTypeLiteral') return type.members.length;
  if (type?.type !== 'TSTypeReference' || type.typeName.type !== 'Identifier') return null;
  const declaration = types.get(type.typeName.name);
  if (declaration?.type === 'TSInterfaceDeclaration') return declaration.body.body.length;
  if (declaration?.type === 'TSTypeAliasDeclaration') return typeMemberCount(declaration.typeAnnotation, types);
  return null;
}

/** The number of props `fn` takes, or null when its first parameter declares none this rule can count. */
function propCount(fn, types) {
  const [param] = fn.params;
  if (param?.type === 'ObjectPattern') return param.properties.length;
  if (param?.type === 'Identifier') return typeMemberCount(param.typeAnnotation?.typeAnnotation, types);
  return null;
}

const maxProps = {
  meta: {
    type: 'suggestion',
    docs: { description: 'Cap the number of props a component takes (GH #311, ADR-0082)' },
    schema: [{ type: 'integer', minimum: 0 }],
    messages: { tooMany: "Component '{{name}}' has too many props ({{count}}). Maximum allowed is {{max}}." },
  },
  create(context) {
    const max = context.options[0] ?? 0;
    let types = new Map();
    const check = (name, fn, node) => {
      if (!isComponentName(name) || !fn) return;
      const count = propCount(fn, types);
      if (count !== null && count > max) context.report({ node, messageId: 'tooMany', data: { name, count, max } });
    };
    return {
      Program(program) {
        types = topLevelTypes(program);
      },
      FunctionDeclaration(node) {
        check(node.id?.name, node, node);
      },
      VariableDeclarator(node) {
        if (node.id.type === 'Identifier') check(node.id.name, functionOf(node.init), node);
      },
    };
  },
};

export default maxProps;
