export function buildTree(list, parentId = 0) {
  const tree = []
  for (const item of list) {
    if (item.parentId === parentId) {
      const children = buildTree(list, item.id)
      if (children.length) item.children = children
      tree.push(item)
    }
  }
  return tree
}

export function flattenTree(tree, result = []) {
  for (const node of tree) {
    const { children, ...rest } = node
    result.push(rest)
    if (children?.length) flattenTree(children, result)
  }
  return result
}
