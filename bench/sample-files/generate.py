N = int(25e2)
for i in range(1, N+1):
  print """class Foo%d {
  def a: Base = null
}
""" % (i)

print "class Base"
