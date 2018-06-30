from argparse import ArgumentParser
import subprocess
import time
import numpy as np

parser = ArgumentParser('Create segments for the input video')
parser.add_argument('video', help='video filename')
parser.add_argument('total', type=int, help='integer for total frame of the video')
parser.add_argument('output', help='output path without file extension')

args = parser.parse_args()

step = 0.5
step_str = '.5'
bias = False
for i in np.arange(0.0, args.total, step):
	start = time.strftime('%H:%M:%S', time.gmtime(i))
	end = time.strftime('%H:%M:%S', time.gmtime(i+step))
	if bias:
		start = str(start) + step_str
		bias = False
	else:
		end = str(end) + step_str
		bias = True
	out = args.output + '_' + str(i+step) + '.mp4'
	subprocess.call(['ffmpeg', '-i', args.video, '-ss', start, '-to', end, '-async', '1', out])
	# print(start, end, out)