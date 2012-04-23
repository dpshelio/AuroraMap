# converted to python from oval.pro from gi.Alaska.edu (Dirk Lummerzheim)
from pylab import *

def boundary(surface,threshold,start=0):
    CS = plt.contour(surface,levels=[threshold])
    level0 = CS.levels[0]
#========================================================
    c0 = CS.collections[0]
    paths = c0.get_paths()

    path_souther = paths[0]
    xy_o_s = path_souther.vertices

    path_norther = paths[1]
    xy_o_n = path_norther.vertices

    south_boundary = [ (hlon[y+start,0],lat[0,x]) for x,y in xy_o_s]
    north_boundary = [ (hlon[y+start,0],lat[0,x]) for x,y in xy_o_n]

    if start != 0:
        north_boundary[-1] = (north_boundary[-1][0]+360,north_boundary[-1][1])
        south_boundary[-1] = (south_boundary[-1][0]+360,south_boundary[-1][1])

    while north_boundary:
        south_boundary.append(north_boundary.pop())

    return south_boundary

time_start ="2003-11-11T21:00:00.000"
time_end ="2003-11-11T05:47:44.000"
kp = 9
hour = 0
mlat = [ 70.0, 68.8, 67.8, 66.5, 65.5, 64.2, 63.1, 61.0, 58.5, 55.]
ww = [ 1.5,  2.8,  4.2,  5.5,  7.0,  8.2,  9.9, 12., 15., 18.5]
lon,lat = np.ogrid[0:364:4,25:90:.25]
hlon = (lon + hour * 15)%360.
h2 = ww[kp]  #width at midnight (deg)
h1 = h2/3    #width at noon (deg)

phi0 = mlat[kp]  #magn. latituded of centroid
f2 = 1      #Brightness at midnigth
f1 = f2     #Brightness at noon

h0 = (h1+h2)/2
rh = (h2-h1)/(h2+h1)

h = h0 * (1 - rh * cos(hlon * pi/180))

f0 = (f1 + f2)/2
rf = (f2-f1)/(f2+f1)
oval = f0*(1-rf*cos(hlon*pi/180.))*exp(-((lat-phi0)/h)**2)
imshow(oval)
#
#coordinates need to be converted to Earth coordinates
#they are right now in magnetic ones.

low_boundary_w = boundary(oval[0:46,:],0.7)
low_boundary_e = boundary(oval[45:,:],0.7,start=45)
high_boundary_w = boundary(oval[0:46,:],0.8)
high_boundary_e = boundary(oval[45:,:],0.8,start=45)

import simplekml
from googlemaps import GoogleMaps
times = simplekml.TimeSpan(begin=time_start,end=time_end)
kml = simplekml.Kml()
style=simplekml.PolyStyle(color='bf7faa00',outline=0)
poly_low_e = kml.newpolygon(name="low",outerboundaryis=low_boundary_e)
poly_low_w = kml.newpolygon(name="low",outerboundaryis=low_boundary_w)
poly_low_e.polystyle = style
poly_low_e.timespan = times
poly_low_w.polystyle = style
poly_low_w.timespan = times
style = simplekml.PolyStyle(color='bf7faaff',outline=0)
poly_high_e = kml.newpolygon(name="high",outerboundaryis=high_boundary_e)
poly_high_w = kml.newpolygon(name="high",outerboundaryis=high_boundary_w)
poly_high_e.polystyle = style
poly_high_e.timespan = times
poly_high_w.polystyle = style
poly_high_w.timespan = times

kml.save('AllPy.kml')
